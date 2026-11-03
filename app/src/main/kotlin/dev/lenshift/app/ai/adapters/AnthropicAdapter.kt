package dev.lenshift.app.ai.adapters

import dev.lenshift.app.ai.ModelInfo
import dev.lenshift.app.ai.ModelListParser
import dev.lenshift.app.ai.ModelSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.Flow
import dev.lenshift.app.ai.AiError
import dev.lenshift.app.ai.ApiProtocol
import dev.lenshift.app.ai.ChatMessage
import dev.lenshift.app.ai.ChatRequest
import dev.lenshift.app.ai.ConnectionTest
import dev.lenshift.app.ai.ProviderProfile
import dev.lenshift.app.ai.ReasoningMapper
import dev.lenshift.app.ai.ReasoningMode
import dev.lenshift.app.ai.StreamEvent
import dev.lenshift.app.ai.transport.HttpSupport
import dev.lenshift.app.ai.transport.ImageEncoder
import org.json.JSONArray
import org.json.JSONObject

/**
 * Native Anthropic Messages API adapter.
 *
 * Differences from Chat Completions that actually matter:
 *  - System prompt is a top-level `system` field, not a message.
 *  - `max_tokens` is **required**.
 *  - Content is an array of typed blocks (`text`, `image`, `thinking`).
 *  - Images are `{"type":"image","source":{"type":"base64","media_type":...,"data":...}}`.
 *  - Streaming is a typed event stream: `content_block_delta` with `text_delta` or
 *    `thinking_delta`, then `message_delta` for stop reason and usage.
 *  - Extended thinking uses `thinking: {type:"enabled", budget_tokens:N}` and requires
 *    `budget_tokens < max_tokens`.
 *
 * Auth uses the `x-api-key` header plus a required `anthropic-version` header.
 */
class AnthropicAdapter : BaseAdapter() {

    override val protocol = ApiProtocol.ANTHROPIC_MESSAGES

    override fun endpoint(profile: ProviderProfile): String =
        "${profile.normalizedBase()}/messages"

    override fun stream(request: ChatRequest): Flow<StreamEvent> {
        val profile = request.profile
        val url = endpoint(profile)
        return streamingPost(
            profile = profile,
            url = url,
            apiKey = request.apiKey,
            scheme = HttpSupport.AuthScheme.X_API_KEY,
            body = buildBody(request),
            transform = ::parseEvent
        )
    }

    private fun buildBody(request: ChatRequest): JSONObject = run {
        val profile = request.profile
        val body = JSONObject()

        body.put("model", profile.modelId)
        body.put("stream", profile.streaming)

        // max_tokens is mandatory for Anthropic.
        var maxTokens = profile.maxOutputTokens ?: DEFAULT_MAX_TOKENS

        val systemText = request.messages
            .filter { it.role == ChatMessage.Role.SYSTEM }
            .joinToString("\n\n") { it.text }
            .trim()
        if (systemText.isNotEmpty()) body.put("system", systemText)

        body.put("messages", buildMessages(request))

        val capabilities = request.capabilities
        if (capabilities.accepts(ModelSpec.TEMPERATURE)) {
            profile.temperature?.let { body.put("temperature", it.toDouble().coerceIn(0.0, 1.0)) }
        }
        if (capabilities.accepts(ModelSpec.TOP_P)) profile.topP?.let { body.put("top_p", it.toDouble()) }
        if (capabilities.accepts(ModelSpec.TOP_K)) profile.topK?.let { body.put("top_k", it) }

        // Thinking may raise max_tokens; apply before writing the final value.
        val requiredMax = ReasoningMapper.applyAnthropic(
            body = body,
            mode = request.reasoningMode,
            capabilities = request.capabilities,
            explicitBudget = profile.reasoningTokenBudget,
            maxTokens = maxTokens
        )
        if (requiredMax != null && requiredMax > maxTokens) maxTokens = requiredMax

        body.put("max_tokens", maxTokens)

        if (profile.extraJsonParameters.isNotBlank()) {
            runCatching {
                val extra = JSONObject(profile.extraJsonParameters)
                extra.keys().forEach { key -> body.put(key, extra.get(key)) }
            }
        }
        body
    }

    private fun buildMessages(request: ChatRequest): JSONArray {
        val array = JSONArray()
        val visionAllowed = request.capabilities.vision != dev.lenshift.app.ai.Tri.NO

        request.messages
            .filter { it.role != ChatMessage.Role.SYSTEM }
            .forEach { message ->
                val role = if (message.role == ChatMessage.Role.ASSISTANT) "assistant" else "user"
                val content = JSONArray()

                if (visionAllowed && role == "user") message.images.forEach { image ->
                    val encoded = ImageEncoder.encode(
                        bitmap = image,
                        maxEdgePx = request.profile.maxImageEdgePx,
                        jpegQuality = request.profile.jpegQuality,
                        detail = request.profile.imageDetail
                    )
                    content.put(
                        JSONObject()
                            .put("type", "image")
                            .put(
                                "source",
                                JSONObject()
                                    .put("type", "base64")
                                    .put("media_type", encoded.mimeType)
                                    .put("data", encoded.base64)
                            )
                    )
                }

                if (message.text.isNotBlank()) {
                    content.put(
                        JSONObject().put("type", "text").put("text", message.text)
                    )
                }

                if (content.length() > 0) {
                    array.put(JSONObject().put("role", role).put("content", content))
                }
            }
        return array
    }

    private fun parseEvent(payload: String): StreamEvent? {
        val trimmed = payload.trim()
        if (trimmed.isEmpty()) return null
        val json = runCatching { JSONObject(trimmed) }.getOrNull() ?: return null

        return when (json.optString("type")) {
            "content_block_delta" -> {
                val delta = json.optJSONObject("delta") ?: return null
                when (delta.optString("type")) {
                    "text_delta" -> delta.optString("text")
                        .takeIf { it.isNotEmpty() }?.let { StreamEvent.TextDelta(it) }
                    "thinking_delta" -> delta.optString("thinking")
                        .takeIf { it.isNotEmpty() }?.let { StreamEvent.ReasoningDelta(it) }
                    // signature_delta carries cryptographic continuation state; it is
                    // replayed by the caller through the full message history, not here.
                    else -> null
                }
            }

            "message_delta" -> {
                val usage = json.optJSONObject("usage")
                if (usage != null && usage.has("output_tokens")) {
                    StreamEvent.Usage(
                        inputTokens = usage.optInt("input_tokens").takeIf { usage.has("input_tokens") },
                        outputTokens = usage.optInt("output_tokens"),
                        reasoningTokens = null
                    )
                } else {
                    null
                }
            }

            "message_start" -> {
                val usage = json.optJSONObject("message")?.optJSONObject("usage")
                usage?.let { u ->
                    StreamEvent.Usage(
                        inputTokens = if (u.has("input_tokens")) u.optInt("input_tokens") else null,
                        outputTokens = null,
                        reasoningTokens = null
                    )
                }
            }

            "error" -> {
                val error = json.optJSONObject("error")
                throw AiError.Server(
                    200,
                    error?.optString("message") ?: "Anthropic reported an error"
                )
            }

            "message_stop", "content_block_stop", "content_block_start" -> null
            else -> null
        }
    }

    override suspend fun listModels(
        profile: ProviderProfile,
        apiKey: String?
    ): List<ModelInfo>? = withContext(Dispatchers.IO) {
        getBody(
            profile, "${profile.normalizedBase()}/models?limit=1000", apiKey,
            HttpSupport.AuthScheme.X_API_KEY, mapOf("anthropic-version" to ANTHROPIC_VERSION)
        )?.let(ModelListParser::anthropic)
    }

    override suspend fun testConnection(
        profile: ProviderProfile,
        apiKey: String?
    ): ConnectionTest = probe(profile, apiKey)

    fun buildBodyForTest(request: ChatRequest): JSONObject = buildBody(request)

    private companion object {
        const val ANTHROPIC_VERSION = "2023-06-01"
        const val DEFAULT_MAX_TOKENS = 4096
    }
}
