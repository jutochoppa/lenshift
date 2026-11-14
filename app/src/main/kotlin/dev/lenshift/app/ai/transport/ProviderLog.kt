package dev.lenshift.app.ai.transport

import android.util.Log
import dev.lenshift.app.BuildConfig
import dev.lenshift.app.ai.ProviderProfile
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import okio.Buffer
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Debug builds only: a record of every error a provider returns. Entries go to logcat and
 * to a file, because logcat on a phone keeps only a minute or so. Read the file with
 *
 *     adb exec-out run-as dev.lenshift.app cat files/debug/provider-errors.log
 *
 * An entry has the HTTP status or the failure, the endpoint, the provider's error body
 * and its diagnostic response headers, and the shape of the request: parameters such as
 * the model, reasoning and token limits in full, but only counts and sizes for messages,
 * instructions and images. API keys, auth and custom header values and URL query values
 * are never written. Release builds write nothing.
 */
object ProviderLog {

    const val TAG = "Lenshift/Provider"
    const val FILE_NAME = "provider-errors.log"
    const val OLD_FILE_NAME = "provider-errors.1.log"

    private const val MAX_BODY_CHARS = 4000
    private const val MAX_VALUE_CHARS = 400
    private const val MAX_STRING_CHARS = 120
    private const val LOGCAT_CHUNK = 3500

    @Volatile
    var enabled: Boolean = BuildConfig.DEBUG

    @Volatile
    internal var maxFileBytes: Long = 256L * 1024

    /** The folder holding the log; null writes to logcat only. */
    @Volatile
    internal var directory: File? = null

    /** Called once by the Application. */
    fun init(filesDir: File) {
        if (enabled) directory = File(filesDir, "debug")
    }

    fun file(): File? = directory?.let { File(it, FILE_NAME) }

    // ------------------------------------------------------------------ entries

    /** The provider answered with an error status. */
    fun httpError(profile: ProviderProfile, request: Request, code: Int, message: String, headers: Headers, body: String?) {
        if (enabled) write(httpErrorEntry(profile, request, code, message, headers, body))
    }

    /** The request got no HTTP answer: DNS, TLS, a timeout or a reset. */
    fun transportError(profile: ProviderProfile, request: Request, error: Throwable) {
        if (enabled) write(transportErrorEntry(profile, request, error))
    }

    /** A failure inside a 200 reply: an error event in the stream, or the stream breaking. */
    fun streamError(profile: ProviderProfile, url: String, apiKey: String?, sent: JSONObject?, payload: String?, error: Throwable) {
        if (enabled) write(streamErrorEntry(profile, url, apiKey, sent, payload, error))
    }

    /** A 200 reply with nothing Lenshift could read in it. */
    fun unreadable(
        profile: ProviderProfile,
        url: String,
        apiKey: String?,
        contentType: String?,
        raw: String,
        reason: String,
        sent: JSONObject? = null
    ) {
        if (enabled) write(unreadableEntry(profile, url, apiKey, contentType, raw, reason, sent))
    }

    /** What the user was told, so each message on screen can be matched to its cause. */
    fun failed(where: String, profile: ProviderProfile?, message: String, error: Throwable? = null) {
        if (enabled) write(failedEntry(where, profile, message, error))
    }

    internal fun httpErrorEntry(
        profile: ProviderProfile,
        request: Request,
        code: Int,
        message: String,
        headers: Headers,
        body: String?
    ): String {
        val secrets = secretsOf(profile, request, null)
        return buildString {
            appendLine("HTTP $code${if (message.isBlank()) "" else " $message"} · ${request.method} ${redactUrl(request.url.toString())}")
            appendLine(providerLine(profile))
            diagnosticHeaders(headers).takeIf { it.isNotEmpty() }?.let { appendLine("headers: $it") }
            appendLine("body: " + (body?.takeIf { it.isNotBlank() }?.let { clip(scrub(it, secrets), MAX_BODY_CHARS) } ?: "(empty)"))
            sentSummary(request)?.let { appendLine("sent: " + scrub(it, secrets)) }
        }.trimEnd()
    }

    internal fun transportErrorEntry(profile: ProviderProfile, request: Request, error: Throwable): String {
        val secrets = secretsOf(profile, request, null)
        return buildString {
            appendLine("FAILED ${error::class.java.simpleName} · ${request.method} ${redactUrl(request.url.toString())}")
            appendLine(providerLine(profile))
            appendLine("error: " + scrub(chain(error), secrets))
            sentSummary(request)?.let { appendLine("sent: " + scrub(it, secrets)) }
        }.trimEnd()
    }

    internal fun streamErrorEntry(
        profile: ProviderProfile,
        url: String,
        apiKey: String?,
        sent: JSONObject?,
        payload: String?,
        error: Throwable
    ): String {
        val secrets = secretsOf(profile, null, apiKey)
        return buildString {
            appendLine("STREAM ERROR · POST ${redactUrl(url)}")
            appendLine(providerLine(profile))
            appendLine("error: " + scrub(chain(error), secrets))
            payload?.let { appendLine("received: " + clip(scrub(it, secrets), MAX_BODY_CHARS)) }
            sent?.let { appendLine("sent: " + scrub(describeBody(it), secrets)) }
        }.trimEnd()
    }

    internal fun unreadableEntry(
        profile: ProviderProfile,
        url: String,
        apiKey: String?,
        contentType: String?,
        raw: String,
        reason: String,
        sent: JSONObject?
    ): String {
        val secrets = secretsOf(profile, null, apiKey)
        return buildString {
            appendLine("UNREADABLE REPLY · ${redactUrl(url)}: $reason")
            appendLine(providerLine(profile))
            contentType?.takeIf { it.isNotBlank() }?.let { appendLine("content-type: $it") }
            appendLine("body: " + (raw.takeIf { it.isNotBlank() }?.let { clip(scrub(it, secrets), MAX_BODY_CHARS) } ?: "(empty)"))
            sent?.let { appendLine("sent: " + scrub(describeBody(it), secrets)) }
        }.trimEnd()
    }

    internal fun failedEntry(where: String, profile: ProviderProfile?, message: String, error: Throwable?): String {
        val secrets = profile?.let { secretsOf(it, null, null) }.orEmpty()
        return buildString {
            appendLine("${where.uppercase(Locale.ROOT)} FAILED, shown: " + scrub(message, secrets))
            profile?.let { appendLine(providerLine(it)) }
            error?.stackTraceToString()?.lineSequence()?.take(10)?.forEach { appendLine("  " + scrub(it, secrets)) }
        }.trimEnd()
    }

    // ---------------------------------------------------------------- redaction

    private fun providerLine(profile: ProviderProfile) =
        "provider: ${profile.displayName} (${profile.protocol.id}), model ${profile.modelId}"

    /** Values that must never be written: keys, auth headers, custom header values. */
    private fun secretsOf(profile: ProviderProfile, request: Request?, apiKey: String?): List<String> = buildList {
        apiKey?.let(::add)
        addAll(profile.customHeaders.values)
        if (request != null) {
            request.header("Authorization")?.let { add(it); add(it.substringAfter(' ').trim()) }
            request.header("x-api-key")?.let(::add)
            request.header("x-goog-api-key")?.let(::add)
            request.url.queryParameter("key")?.let(::add)
            profile.customHeaders.keys.forEach { name -> request.header(name)?.let(::add) }
        }
    }.filter { it.length >= 6 }.distinct().sortedByDescending { it.length }

    private val BEARER = Regex("""(?i)bearer\s+[A-Za-z0-9._~+/=\-]{8,}""")
    private val KEY_LIKE = Regex("""\b(?:sk|pk|rk)-[A-Za-z0-9_\-]{12,}|\bAIza[0-9A-Za-z_\-]{20,}""")
    private val BASE64_RUN = Regex("""[A-Za-z0-9+/]{400,}={0,2}""")

    internal fun scrub(text: String, secrets: List<String>): String {
        var out = text
        for (secret in secrets) out = out.replace(secret, "<hidden>")
        out = BEARER.replace(out, "Bearer <hidden>")
        out = KEY_LIKE.replace(out) { it.value.take(3) + "<hidden>" }
        return BASE64_RUN.replace(out) { "<${it.value.length} chars of base64>" }
    }

    /** The endpoint without user info or query values; the query's names stay. */
    internal fun redactUrl(url: String): String {
        val parsed = url.toHttpUrlOrNull() ?: return url.substringBefore('?') + if ('?' in url) "?…" else ""
        val base = parsed.newBuilder().username("").password("").query(null).fragment(null).build().toString()
        val names = parsed.queryParameterNames
        return if (names.isEmpty()) base else base + "?" + names.joinToString("&") { "$it=…" }
    }

    private val HEADER_NAMES = setOf(
        "content-type", "content-length", "retry-after", "server", "via", "cf-ray", "cf-cache-status",
        "x-request-id", "request-id", "x-amzn-requestid", "openai-processing-ms", "openai-version",
        "x-envoy-upstream-service-time"
    )

    /** Response headers that help diagnose a failure; cookies and the rest are left out. */
    internal fun diagnosticHeaders(headers: Headers): String = headers.names()
        .map { it.lowercase(Locale.ROOT) }
        .distinct()
        .filter { n ->
            n in HEADER_NAMES || n.startsWith("x-ratelimit-") || n.startsWith("anthropic-ratelimit-") ||
                n.endsWith("-request-id") || n.endsWith("-trace-id")
        }
        .sorted()
        .joinToString(", ") { "$it: ${headers[it]}" }

    private fun chain(error: Throwable): String = generateSequence(error) { it.cause }
        .take(4)
        .joinToString(" ← ") { "${it::class.java.simpleName}: ${it.message.orEmpty()}" }

    private fun clip(text: String, max: Int) =
        if (text.length <= max) text else text.take(max) + "… (${text.length} chars)"

    // ------------------------------------------------------------ request shape

    private fun sentSummary(request: Request): String? {
        val body = request.body ?: return null
        if (body.isOneShot() || body.isDuplex()) return "(body not readable)"
        val text = runCatching { Buffer().also { body.writeTo(it) }.readUtf8() }.getOrNull() ?: return null
        val json = runCatching { JSONObject(text) }.getOrNull() ?: return "${text.length} chars, not JSON"
        return describeBody(json)
    }

    /** Parameters, model and limits word for word; conversation content as sizes only. */
    private val CONTENT_KEYS = setOf(
        "messages", "input", "contents", "prompt", "instructions", "system", "systemInstruction", "system_instruction"
    )
    private val SECRET_NAME = Regex("""(?i)(key$|token$|secret|passw|auth|cookie|credential|bearer|signature)""")
    private val FIRST_KEYS = listOf("model", "stream")

    internal fun describeBody(json: JSONObject): String {
        val keys = json.keys().asSequence().toList()
        val ordered = FIRST_KEYS.filter { it in keys } + keys.filterNot { it in FIRST_KEYS }.sorted()
        return ordered.joinToString(" ") { key -> "$key=" + describeValue(key, json.opt(key)) }
    }

    private fun describeValue(key: String, value: Any?): String = when {
        SECRET_NAME.containsMatchIn(key) -> "<hidden>"
        key in CONTENT_KEYS -> contentShape(value)
        value is JSONObject || value is JSONArray -> clip(parameters(value).toString(), MAX_VALUE_CHARS)
        value is String -> "\"" + clip(value, MAX_STRING_CHARS) + "\""
        else -> value.toString()
    }

    /** A copy of a parameter object with secrets hidden and any nested content sized. */
    private fun parameters(value: Any?): Any? = when (value) {
        is JSONObject -> JSONObject().also { out ->
            for (k in value.keys()) out.put(
                k,
                when {
                    SECRET_NAME.containsMatchIn(k) -> "<hidden>"
                    k in CONTENT_KEYS -> contentShape(value.opt(k))
                    else -> parameters(value.opt(k))
                }
            )
        }
        is JSONArray -> JSONArray().also { out -> for (i in 0 until value.length()) out.put(parameters(value.opt(i))) }
        is String -> clip(value, MAX_STRING_CHARS)
        else -> value
    }

    private class Tally {
        val roles = LinkedHashMap<String, Int>()
        var textChars = 0L
        var images = 0
        var imageChars = 0L
    }

    private val IMAGE_TYPES = setOf("input_image", "image_url", "image")
    private val META_KEYS = setOf(
        "role", "type", "detail", "mime_type", "mimeType", "media_type", "id", "status", "phase", "cache_control"
    )

    private fun contentShape(value: Any?): String = when (value) {
        is String -> "[text ${value.length} chars]"
        is JSONArray, is JSONObject -> {
            val tally = Tally().also { count(value, it, null) }
            buildList {
                if (value is JSONArray) {
                    val roles = tally.roles.entries.joinToString(", ") { "${it.key} ${it.value}" }
                    add("${value.length()} items" + if (roles.isEmpty()) "" else ": $roles")
                }
                add("text ${tally.textChars} chars")
                if (tally.images > 0) add("images ${tally.images}, ≈${size(tally.imageChars * 3 / 4)}")
            }.joinToString("; ", "[", "]")
        }
        null, JSONObject.NULL -> "null"
        else -> value.toString()
    }

    private fun count(value: Any?, tally: Tally, key: String?) {
        when (value) {
            is JSONObject -> {
                value.optString("role").takeIf { it.isNotEmpty() }?.let { tally.roles[it] = (tally.roles[it] ?: 0) + 1 }
                if (value.optString("type") in IMAGE_TYPES || value.has("inline_data") || value.has("inlineData")) {
                    tally.images++
                }
                for (k in value.keys()) count(value.opt(k), tally, k)
            }
            is JSONArray -> for (i in 0 until value.length()) count(value.opt(i), tally, key)
            is String -> when {
                key == "images" -> { tally.images++; tally.imageChars += value.length } // Ollama
                value.startsWith("data:") || looksBase64(value) -> tally.imageChars += value.length
                key in META_KEYS -> Unit
                else -> tally.textChars += value.length
            }
        }
    }

    private fun looksBase64(text: String) =
        text.length >= 400 && text.all { it in 'A'..'Z' || it in 'a'..'z' || it in '0'..'9' || it == '+' || it == '/' || it == '=' }

    private fun size(bytes: Long) = if (bytes < 1024) "$bytes B" else "${bytes / 1024} KB"

    // ------------------------------------------------------------------ writing

    @Synchronized
    private fun write(entry: String) {
        val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS XXX", Locale.US).format(Date())
        val text = "=== $stamp\n$entry\n"
        text.chunked(LOGCAT_CHUNK).forEach { Log.w(TAG, it) }
        val folder = directory ?: return
        runCatching {
            folder.mkdirs()
            val file = File(folder, FILE_NAME)
            if (file.exists() && file.length() + text.length > maxFileBytes) {
                val old = File(folder, OLD_FILE_NAME)
                old.delete()
                file.renameTo(old)
            }
            file.appendText(text)
        }
    }
}
