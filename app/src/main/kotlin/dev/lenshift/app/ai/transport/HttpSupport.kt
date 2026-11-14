package dev.lenshift.app.ai.transport

import dev.lenshift.app.ai.AiError
import dev.lenshift.app.ai.ProviderProfile
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.net.MalformedURLException
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLException

/**
 * Shared HTTP plumbing: client construction, URL validation, and error mapping.
 *
 * Privacy rules enforced here:
 *  - Redirects are NOT followed automatically. A provider could otherwise bounce a
 *    request (with its Authorization header) to a third-party host.
 *  - Cleartext HTTP is refused unless the profile explicitly opts in, and only for
 *    loopback / private-range hosts.
 */
object HttpSupport {

    /** Hosts where plain HTTP is legitimate (local model servers). */
    private val loopbackHosts = setOf("localhost", "127.0.0.1", "::1", "10.0.2.2")

    private val clients = HashMap<Long, OkHttpClient>()

    @Synchronized
    fun clientFor(timeoutMs: Long): OkHttpClient =
        clients.getOrPut(timeoutMs) {
            OkHttpClient.Builder()
                .connectTimeout(minOf(timeoutMs, 20_000L), TimeUnit.MILLISECONDS)
                .readTimeout(timeoutMs, TimeUnit.MILLISECONDS)
                .writeTimeout(timeoutMs, TimeUnit.MILLISECONDS)
                .callTimeout(timeoutMs + 10_000L, TimeUnit.MILLISECONDS)
                .followRedirects(false)
                .followSslRedirects(false)
                .retryOnConnectionFailure(false)
                .build()
        }

    /**
     * Validates and normalises a profile's base URL.
     *
     * @throws AiError.InvalidEndpoint when the scheme/host is unusable.
     */
    fun validateBase(profile: ProviderProfile): URL {
        val raw = profile.normalizedBase()
        if (raw.isBlank()) {
            throw AiError.InvalidEndpoint("Base URL is empty")
        }
        val url = try {
            URL(raw)
        } catch (e: MalformedURLException) {
            throw AiError.InvalidEndpoint("'$raw' is not a valid URL")
        }

        when (url.protocol.lowercase()) {
            "https" -> Unit
            "http" -> {
                if (!profile.allowCleartext) {
                    throw AiError.InvalidEndpoint(
                        "Plain HTTP is off for this provider. Use https://, or turn on " +
                            "\"Allow plain HTTP\" under Behaviour in this provider's settings."
                    )
                }
                val host = url.host.lowercase()
                val isPrivate = host in loopbackHosts ||
                    host.startsWith("192.168.") ||
                    host.startsWith("10.") ||
                    host.matches(Regex("""172\.(1[6-9]|2\d|3[01])\..*"""))
                if (!isPrivate) {
                    throw AiError.InvalidEndpoint(
                        "Plain HTTP is only allowed for local/private hosts, not '$host'"
                    )
                }
            }
            else -> throw AiError.InvalidEndpoint(
                "Unsupported scheme '${url.protocol}'. Use http or https."
            )
        }
        return url
    }

    /**
     * Executes a request and maps transport/HTTP failures onto [AiError].
     * The caller owns the returned [Response] and must close it.
     */
    fun execute(profile: ProviderProfile, request: Request): Response {
        val client = clientFor(profile.timeoutMs)
        val call = client.newCall(request)

        // OkHttp's execute() blocks and is not interrupted by coroutine cancellation, so
        // Stop used to leave the socket open while the provider kept generating (and
        // billing) for up to the full call timeout. Register the Call with the current
        // cancellation scope so cancelling the coroutine cancels the HTTP call itself.
        val registration = CallRegistry.register(call)
        val response = try {
            call.execute()
        } catch (e: IOException) {
            // A call cut short by Stop is not a provider failure; only real ones are logged.
            if (!call.isCanceled()) ProviderLog.transportError(profile, request, e)
            when (e) {
                is SocketTimeoutException ->
                    throw AiError.Timeout("The provider did not respond in ${profile.timeoutMs} ms")
                is UnknownHostException -> throw AiError.Network("Could not resolve '${e.message}'")
                is SSLException -> throw AiError.Network("TLS handshake failed: ${e.message}", e)
            }
            if (call.isCanceled()) {
                throw kotlinx.coroutines.CancellationException("Request cancelled")
            }
            throw AiError.Network(e.message ?: "Connection failed", e)
        } finally {
            registration.close()
        }

        if (!response.isSuccessful) {
            val body = runCatching { response.body?.string() }.getOrNull()
            ProviderLog.httpError(profile, request, response.code, response.message, response.headers, body)
            val detail = extractErrorDetail(body) ?: response.message.ifBlank { "HTTP ${response.code}" }
            val error = when (response.code) {
                401, 403 -> AiError.Auth(detail)
                404 -> AiError.NotFound(
                    "$detail — check the base URL and model id"
                )
                400, 422 -> AiError.BadRequest(detail, body)
                408 -> AiError.Timeout(detail)
                429 -> AiError.RateLimited(detail)
                in 500..599 -> AiError.Server(response.code, detail)
                in 300..399 -> AiError.Protocol(
                    "The provider returned a redirect (${response.code}) to " +
                        "'${response.header("Location")}'. Lenshift does not follow " +
                        "redirects, because doing so would forward your API key. " +
                        "Use the final URL directly."
                )
                else -> AiError.Protocol("HTTP ${response.code}: $detail")
            }
            response.close()
            throw error
        }
        return response
    }

    /** Pulls a human-readable message out of the usual error envelopes. */
    private fun extractErrorDetail(body: String?): String? {
        if (body.isNullOrBlank()) return null
        return runCatching {
            val json = org.json.JSONObject(body)
            when {
                json.has("error") -> {
                    val error = json.get("error")
                    if (error is org.json.JSONObject) {
                        error.optString("message").ifBlank { error.toString() }
                    } else {
                        error.toString()
                    }
                }
                json.has("message") -> json.optString("message")
                json.has("detail") -> json.optString("detail")
                else -> body.take(400)
            }
        }.getOrElse { body.take(400) }
    }

    /** Builds a request with the profile's auth + custom headers. */
    fun requestBuilder(
        profile: ProviderProfile,
        url: String,
        apiKey: String?,
        authScheme: AuthScheme
    ): Request.Builder {
        // Enforce the endpoint policy here, not only in post(). This is the single place
        // every authenticated request is constructed, including model discovery, which
        // previously bypassed the check entirely: "Refresh models" would happily send an
        // API key in cleartext to a public http:// host that chat requests refused.
        validateBase(profile)

        val builder = Request.Builder()
            .url(url)
            .header("Accept", "text/event-stream, application/json")
            .header("User-Agent", "Lenshift/1.0 (Android)")

        when (authScheme) {
            AuthScheme.BEARER -> if (!apiKey.isNullOrBlank()) {
                builder.header("Authorization", "Bearer $apiKey")
            }
            AuthScheme.X_API_KEY -> if (!apiKey.isNullOrBlank()) {
                builder.header("x-api-key", apiKey)
            }
            AuthScheme.X_GOOG_API_KEY -> if (!apiKey.isNullOrBlank()) {
                builder.header("x-goog-api-key", apiKey)
            }
            AuthScheme.QUERY_PARAM -> Unit // adapter appends ?key=...
            AuthScheme.NONE -> Unit
        }

        // User headers last so they can override anything above (e.g. a proxy that
        // needs its own auth header instead of a bearer token).
        profile.customHeaders.forEach { (name, value) ->
            if (name.isNotBlank() && value.isNotBlank()) builder.header(name, value)
        }
        return builder
    }

    enum class AuthScheme { BEARER, X_API_KEY, X_GOOG_API_KEY, QUERY_PARAM, NONE }

    /** Never log an Authorization header; this exists so call sites cannot. */
    fun redactedHeaders(request: Request): Map<String, String> =
        request.headers.names().associate { name ->
            val value = request.header(name).orEmpty()
            name to if (name.equals("Authorization", true) ||
                name.equals("x-api-key", true) ||
                name.equals("x-goog-api-key", true) ||
                name.contains("token", true) ||
                name.contains("key", true)
            ) {
                "***redacted***"
            } else {
                value
            }
        }
}
