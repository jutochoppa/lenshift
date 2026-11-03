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
import dev.lenshift.app.ai.transport.SseParser
import kotlinx.coroutines.CancellationException
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
                if (raw.isNotBlank()) {
                    transform(raw)?.let { emit(it) }
                }
            } else {
                SseParser.parse(response).collect { event ->
                    val mapped = try {
                        transform(event.data)
                    } catch (e: AiError) {
                        throw e
                    } catch (e: Exception) {
                        // A single malformed frame must not kill the stream.
                        null
                    }
                    if (mapped != null) emit(mapped)
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
        throw AiError.Protocol("Stream failed: $detail")
    }

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
                throw AiError.Protocol("Expected JSON, got '$contentType'")
            }
            dev.lenshift.app.ai.transport.NdjsonParser.parse(response).collect { line ->
                val mapped = try {
                    transform(line)
                } catch (e: AiError) {
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
            ConnectionTest(
                ok = false,
                headline = e::class.simpleName ?: "Error",
                detail = e.message ?: "Unknown failure",
                latencyMs = System.currentTimeMillis() - started
            )
        } catch (e: Exception) {
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
    }
}
