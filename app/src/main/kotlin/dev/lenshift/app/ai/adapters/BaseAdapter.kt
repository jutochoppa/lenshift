package dev.lenshift.app.ai.adapters

import dev.lenshift.app.ai.AiError
import dev.lenshift.app.ai.ChatMessage
import dev.lenshift.app.ai.ChatRequest
import dev.lenshift.app.ai.ChatResult
import dev.lenshift.app.ai.ConnectionTest
import dev.lenshift.app.ai.ModelInfo
import dev.lenshift.app.ai.ProviderAdapter
import dev.lenshift.app.ai.ProviderProfile
import dev.lenshift.app.ai.ReasoningMode
import dev.lenshift.app.ai.StreamEvent
import dev.lenshift.app.ai.transport.HttpSupport
import dev.lenshift.app.ai.transport.ProviderLog
import dev.lenshift.app.ai.transport.SseParser
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

/**
 * Shared plumbing so every adapter reports the same failure taxonomy and the same
 * `complete()` semantics.
 */
abstract class BaseAdapter : ProviderAdapter {

    override suspend fun complete(request: ChatRequest): ChatResult {
        val text = StringBuilder()
        val reasoning = StringBuilder()
        var usage: StreamEvent.Usage? = null
        var continuation: String? = null

        try {
            stream(request).collect { event ->
                when (event) {
                    is StreamEvent.TextDelta -> text.append(event.text)
                    is StreamEvent.ReasoningDelta -> reasoning.append(event.text)
                    is StreamEvent.Usage -> usage = event
                    is StreamEvent.Notice -> Unit
                    StreamEvent.Completed -> {
                        if (continuation == null) continuation = continuationFromStream()
                    }
                }
            }
        } catch (e: CancellationException) {
            throw e
        }

        val answer = text.toString()
        if (answer.isBlank()) {
            if (reasoning.isNotEmpty()) {
                throw AiError.ReasoningStarved(usage?.reasoningTokens)
            }
            throw AiError.Protocol(
                "The provider returned no text. This usually means the model id is " +
                    "wrong, the model refused, or the output budget was exhausted."
            )
        }

        return ChatResult(
            text = answer,
            reasoningSummary = reasoning.toString().ifBlank { null },
            continuationToken = continuation,
            usage = usage,
            finishReason = null
        )
    }

    /** Adapters that keep provider-side state override this. */
    protected open fun continuationFromStream(): String? = null

    override suspend fun listModels(profile: ProviderProfile, apiKey: String?): List<ModelInfo>? = null

    /**
     * GETs [url] and returns its body, or null when the endpoint has no such list (404,
     * 400, an unexpected reply). Auth, network and rate-limit failures are thrown so the
     * UI can say what went wrong.
     */
    protected fun getBody(
        profile: ProviderProfile,
        url: String,
        apiKey: String?,
        scheme: HttpSupport.AuthScheme,
        headers: Map<String, String> = emptyMap()
    ): String? = try {
        val builder = HttpSupport.requestBuilder(profile, url, apiKey, scheme)
        headers.forEach { (name, value) -> builder.header(name, value) }
        HttpSupport.execute(profile, builder.get().build()).use { it.body?.string() }
    } catch (e: AiError.NotFound) {
        null
    } catch (e: AiError.BadRequest) {
        null
    } catch (e: AiError.Protocol) {
        null
    }

    /**
     * Executes the POST and hands the open [okhttp3.Response] to [block], closing it
     * exactly once. URL validation happens first so bad endpoints never reach the wire.
     */
    protected suspend fun <T> post(
        profile: ProviderProfile,
        url: String,
        apiKey: String?,
        scheme: HttpSupport.AuthScheme,
        body: JSONObject,
        block: suspend (okhttp3.Response) -> T
    ): T {
        HttpSupport.validateBase(profile)
        val request = HttpSupport.requestBuilder(profile, url, apiKey, scheme)
            .header("Content-Type", "application/json")
            .post(body.toString().toRequestBody(JSON_MEDIA))
            .build()
        val response = HttpSupport.execute(profile, request)
        return response.use { block(it) }
    }

    /**
     * Streams a response, tolerating servers that ignore `stream: true` and return a
     * single JSON document instead of an SSE body.
     *
     * @param transform maps one SSE `data:` payload (or a whole JSON body) to an event.
     */
    protected fun streamingPost(
        profile: ProviderProfile,
        url: String,
        apiKey: String?,
        scheme: HttpSupport.AuthScheme,
        body: JSONObject,
        transform: (String) -> StreamEvent?
    ): Flow<StreamEvent> = flow {
        post(profile, url, apiKey, scheme, body) { response ->
            val contentType = response.header("Content-Type").orEmpty()
            val isSse = contentType.contains("event-stream", ignoreCase = true)

            if (!isSse) {
                val raw = response.body?.string().orEmpty()
                val event = if (raw.isBlank()) null else try {
                    transform(raw)
                } catch (e: AiError) {
                    ProviderLog.streamError(profile, url, apiKey, body, raw, e)
                    throw e
                }
                if (event != null) {
                    emit(event)
                } else {
                    ProviderLog.unreadable(profile, url, apiKey, contentType, raw, "nothing to read in a non-streamed reply", body)
                }
            } else {
                // Debug builds note what arrived, so a stream with no answer in it can be
                // logged with the kinds of event it did have.
                val seen = if (ProviderLog.enabled) LinkedHashMap<String, Int>() else null
                var sample: String? = null
                var answered = false
                SseParser.parse(response).collect { event ->
                    val mapped = try {
                        transform(event.data)
                    } catch (e: AiError) {
                        ProviderLog.streamError(profile, url, apiKey, body, render(event), e)
                        throw e
                    } catch (e: Exception) {
                        // A single malformed frame must not kill the stream.
                        null
                    }
                    // An error event the adapter doesn't know (a relay's own) ends the
                    // answer with its message instead of leaving it empty.
                    if (mapped == null) {
                        val kind = event.name ?: EVENT_TYPE.find(event.data.take(200))?.groupValues?.get(1)
                        if (StreamErrors.isError(kind)) {
                            val error = AiError.Server(200, StreamErrors.message(event.data) ?: "The provider sent $kind")
                            ProviderLog.streamError(profile, url, apiKey, body, render(event), error)
                            throw error
                        }
                    }
                    if (seen != null) {
                        val kind = event.name ?: EVENT_TYPE.find(event.data.take(200))?.groupValues?.get(1) ?: "data"
                        if (seen.size < 20 || kind in seen) seen[kind] = (seen[kind] ?: 0) + 1
                        if (mapped == null && sample == null) sample = render(event)
                        if (mapped is StreamEvent.TextDelta || mapped is StreamEvent.ReasoningDelta) answered = true
                    }
                    if (mapped != null) emit(mapped)
                }
                if (seen != null && !answered) {
                    val kinds = seen.entries.joinToString(", ") { "${it.key} ×${it.value}" }.ifEmpty { "none" }
                    ProviderLog.unreadable(
                        profile, url, apiKey, contentType,
                        "events: $kinds\nfirst unread: ${sample ?: "-"}",
                        "the stream ended without any answer text", body
                    )
                }
            }
        }
        emit(StreamEvent.Completed)
    }.catch { t ->
        if (t is CancellationException) throw t
        if (t is AiError) throw t
        // Include the exception type: "Stream failed" alone is undiagnosable, and the
        // difference between a socket reset, a TLS failure and a parse error is exactly
        // what a user (or a bug report) needs to see.
        val detail = t.message?.takeIf { it.isNotBlank() } ?: t::class.simpleName ?: "unknown"
        if (currentCoroutineContext().isActive) ProviderLog.streamError(profile, url, apiKey, body, null, t)
        throw AiError.Protocol("Stream failed: $detail")
    }

    private fun render(event: SseParser.Event) =
        if (event.name != null) "event: ${event.name}\n${event.data}" else event.data

    /** Newline-delimited JSON variant (Ollama). */
    protected fun ndjsonPost(
        profile: ProviderProfile,
        url: String,
        apiKey: String?,
        scheme: HttpSupport.AuthScheme,
        body: JSONObject,
        transform: (String) -> StreamEvent?
    ): Flow<StreamEvent> = flow {
        post(profile, url, apiKey, scheme, body) { response ->
            val contentType = response.header("Content-Type").orEmpty()
            if (!contentType.contains("json", ignoreCase = true)) {
                if (ProviderLog.enabled) {
                    val raw = runCatching { response.body?.string() }.getOrNull().orEmpty()
                    ProviderLog.unreadable(profile, url, apiKey, contentType, raw, "expected JSON lines", body)
                }
                throw AiError.Protocol("Expected JSON, got '$contentType'")
            }
            dev.lenshift.app.ai.transport.NdjsonParser.parse(response).collect { line ->
                val mapped = try {
                    transform(line)
                } catch (e: AiError) {
                    ProviderLog.streamError(profile, url, apiKey, body, line, e)
                    throw e
                } catch (e: Exception) {
                    null
                }
                if (mapped != null) emit(mapped)
            }
        }
        emit(StreamEvent.Completed)
    }.catch { t ->
        if (t is CancellationException) throw t
        if (t is AiError) throw t
        // Include the exception type: "Stream failed" alone is undiagnosable, and the
        // difference between a socket reset, a TLS failure and a parse error is exactly
        // what a user (or a bug report) needs to see.
        val detail = t.message?.takeIf { it.isNotBlank() } ?: t::class.simpleName ?: "unknown"
        if (currentCoroutineContext().isActive) ProviderLog.streamError(profile, url, apiKey, body, null, t)
        throw AiError.Protocol("Stream failed: $detail")
    }

    /** Fires one minimal request to prove the endpoint, key and model actually work. */
    protected suspend fun probe(profile: ProviderProfile, apiKey: String?): ConnectionTest {
        val started = System.currentTimeMillis()
        return try {
            val result = complete(
                ChatRequest(
                    profile = profile,
                    apiKey = apiKey,
                    messages = listOf(
                        ChatMessage(ChatMessage.Role.USER, "Reply with exactly: OK")
                    ),
                    capabilities = dev.lenshift.app.ai.CapabilityInference.infer(
                        profile.protocol, profile.modelId
                    ),
                    reasoningMode = ReasoningMode.AUTO
                )
            )
            ConnectionTest(
                ok = true,
                headline = "Connected",
                detail = "Model replied: \"${result.text.trim().take(80)}\"",
                latencyMs = System.currentTimeMillis() - started
            )
        } catch (e: AiError) {
            ProviderLog.failed("connection test", profile, e.message ?: "Unknown failure")
            ConnectionTest(
                ok = false,
                headline = e::class.simpleName ?: "Error",
                detail = e.message ?: "Unknown failure",
                latencyMs = System.currentTimeMillis() - started
            )
        } catch (e: Exception) {
            if (e !is CancellationException) ProviderLog.failed("connection test", profile, e.message ?: "Unknown failure", e)
            ConnectionTest(
                ok = false,
                headline = "Error",
                detail = e.message ?: "Unknown failure",
                latencyMs = System.currentTimeMillis() - started
            )
        }
    }

    protected companion object {
        val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()

        /** The `type` of a JSON event, for the debug log's tally of what a stream carried. */
        val EVENT_TYPE = Regex(""""type"\s*:\s*"([^"]{1,60})"""")
    }
}
