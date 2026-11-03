package dev.lenshift.app.ai.adapters

import dev.lenshift.app.ai.ModelInfo
import dev.lenshift.app.ai.ModelListParser
import dev.lenshift.app.ai.ModelSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.Flow
import dev.lenshift.app.ai.ApiProtocol
import dev.lenshift.app.ai.CapabilityInference
import dev.lenshift.app.ai.ChatMessage
import dev.lenshift.app.ai.ChatRequest
import dev.lenshift.app.ai.ConnectionTest
import dev.lenshift.app.ai.ModelCapabilities
import dev.lenshift.app.ai.ProviderProfile
import dev.lenshift.app.ai.ReasoningMapper
import dev.lenshift.app.ai.StreamEvent
import dev.lenshift.app.ai.transport.HttpSupport
import dev.lenshift.app.ai.transport.ImageEncoder
import org.json.JSONArray
import org.json.JSONObject

/**
 * [OI]-compatible Chat Completions adapter.
 *
 * Targets `POST {base}/chat/completions`. This shape is cloned by a very large number of
 * servers, so one adapter covers [OI] itself, OpenRouter, LiteLLM, vLLM, LM Studio,
 * llama.cpp's server, LocalAI, Groq, Together, Fireworks, DeepSeek, Moonshot, xAI and
 * most self-hosted proxies.
 *
 * Deliberate choices:
 *  - The model id is passed through **verbatim**. No provider prefix is prepended; if a
 *    gateway wants `openai/gpt-4o` the user types exactly that.
 *  - `reasoning_effort` vs `reasoning:{effort}` is selected from the base URL, because
 *    OpenRouter documents the nested form and first-party [OI] documents the flat one.
 *  - Images use the `image_url` content-part shape, which is the de-facto standard.
 */
class OpenAiChatAdapter : BaseAdapter() {

    override val protocol = ApiProtocol.OPENAI_CHAT

    override fun endpoint(profile: ProviderProfile): String =
        "${profile.normalizedBase()}/chat/completions"

    private fun isOpenRouter(profile: ProviderProfile): Boolean =
        profile.normalizedBase().contains("openrouter.ai", ignoreCase = true)

    private fun isOpenAi(profile: ProviderProfile): Boolean =
        profile.normalizedBase().contains("api.openai.com", ignoreCase = true)

    override fun stream(request: ChatRequest): Flow<StreamEvent> =
        streamingPost(
            profile = request.profile,
            url = endpoint(request.profile),
            apiKey = request.apiKey,
            scheme = HttpSupport.AuthScheme.BEARER,
            body = buildBody(request),
            transform = ::parseChunk
        )

    private fun buildBody(request: ChatRequest): JSONObject = run {
        val profile = request.profile
        val capabilities = request.capabilities

        val body = JSONObject()
        body.put("model", profile.modelId)
        body.put("stream", profile.streaming)

        // Ask for usage on the final chunk where the provider supports it.
        if (profile.streaming) {
            body.put("stream_options", JSONObject().put("include_usage", true))
        }

        body.put("messages", buildMessages(request, capabilities))

        // Models that list their parameters (OpenAI reasoning models take no temperature)
        // only get the ones they accept; a rejected parameter fails the whole request.
        if (capabilities.accepts(ModelSpec.TEMPERATURE)) profile.temperature?.let { body.put("temperature", it.toDouble()) }
        if (capabilities.accepts(ModelSpec.TOP_P)) profile.topP?.let { body.put("top_p", it.toDouble()) }
        if (capabilities.accepts(ModelSpec.TOP_K)) profile.topK?.let { body.put("top_k", it) }
        // OpenAI's own API replaced max_tokens, which its reasoning models reject.
        val maxField = if (isOpenAi(profile)) "max_completion_tokens" else "max_tokens"
        profile.maxOutputTokens?.let { body.put(maxField, it) }

        ReasoningMapper.applyOpenAiChat(
            body = body,
            mode = request.reasoningMode,
            capabilities = capabilities,
            openRouterStyle = isOpenRouter(profile)
        )

        mergeExtra(body, profile)
        body
    }

    private fun buildMessages(
        request: ChatRequest,
        capabilities: ModelCapabilities
    ): JSONArray {
        val array = JSONArray()
        val visionAllowed = capabilities.vision != dev.lenshift.app.ai.Tri.NO

        request.messages.forEach { message ->
            when (message.role) {
                ChatMessage.Role.SYSTEM -> array.put(
                    JSONObject()
                        .put("role", "system")
                        .put("content", message.text)
                )

                ChatMessage.Role.ASSISTANT -> array.put(
                    JSONObject()
                        .put("role", "assistant")
                        .put("content", message.text)
                )

                ChatMessage.Role.USER -> {
                    if (message.images.isNotEmpty() && visionAllowed) {
                        val parts = JSONArray()
                        if (message.text.isNotBlank()) {
                            parts.put(JSONObject().put("type", "text").put("text", message.text))
                        }
                        message.images.forEach { image ->
                            val encoded = ImageEncoder.encode(
                                bitmap = image,
                                maxEdgePx = request.profile.maxImageEdgePx,
                                jpegQuality = request.profile.jpegQuality,
                                detail = request.profile.imageDetail
                            )
                            parts.put(
                                JSONObject()
                                    .put("type", "image_url")
                                    .put(
                                        "image_url",
                                        JSONObject()
                                            .put("url", "data:${encoded.mimeType};base64,${encoded.base64}")
                                            .put("detail", request.profile.imageDetail.id)
                                    )
                            )
                        }
                        array.put(
                            JSONObject().put("role", "user").put("content", parts)
                        )
                    } else {
                        // Text-only model or no image: never silently drop the text.
                        array.put(
                            JSONObject().put("role", "user").put("content", message.text)
                        )
                    }
                }
            }
        }
        return array
    }

    private fun parseChunk(payload: String): StreamEvent? {
        val trimmed = payload.trim()
        if (trimmed.isEmpty()) return null

        val json = runCatching { JSONObject(trimmed) }.getOrNull() ?: return null

        // Some gateways emit an error object mid-stream.
        json.optJSONObject("error")?.let { error ->
            throw dev.lenshift.app.ai.AiError.Server(
                200,
                error.optString("message").ifBlank { error.toString() }
            )
        }

        // Usage-only final chunk.
        json.optJSONObject("usage")?.let { usage ->
            val input = usage.optIntOrNull("prompt_tokens")
            val output = usage.optIntOrNull("completion_tokens")
            val reasoning = usage.optJSONObject("completion_tokens_details")
                ?.optIntOrNull("reasoning_tokens")
            if (input != null || output != null) {
                // Emit usage and continue; a chunk can carry both usage and a delta.
                val choices = json.optJSONArray("choices")
                if (choices == null || choices.length() == 0) {
                    return StreamEvent.Usage(input, output, reasoning)
                }
            }
        }

        val choices = json.optJSONArray("choices") ?: return null
        if (choices.length() == 0) return null

        val choice = choices.optJSONObject(0) ?: return null
        val delta = choice.optJSONObject("delta") ?: choice.optJSONObject("message")

        // Reasoning-capable clones expose a summary in one of several fields.
        val reasoningText = delta?.optString("reasoning_content")
            ?: delta?.optString("reasoning")
            ?: delta?.optString("thinking")
        if (!reasoningText.isNullOrEmpty()) {
            return StreamEvent.ReasoningDelta(reasoningText)
        }

        val content = delta?.opt("content")
        val text = when (content) {
            is String -> content
            is JSONArray -> content.optJSONObject(0)?.optString("text").orEmpty()
            else -> null
        }

        return if (!text.isNullOrEmpty()) StreamEvent.TextDelta(text) else null
    }

    override suspend fun listModels(
        profile: ProviderProfile,
        apiKey: String?
    ): List<ModelInfo>? = withContext(Dispatchers.IO) {
        getBody(profile, "${profile.normalizedBase()}/models", apiKey, HttpSupport.AuthScheme.BEARER)
            ?.let(ModelListParser::openAiStyle)
    }

    override suspend fun testConnection(
        profile: ProviderProfile,
        apiKey: String?
    ): ConnectionTest = probe(profile, apiKey)

    private fun mergeExtra(body: JSONObject, profile: ProviderProfile) {
        if (profile.extraJsonParameters.isBlank()) return
        runCatching {
            val extra = JSONObject(profile.extraJsonParameters)
            extra.keys().forEach { key -> body.put(key, extra.get(key)) }
        }
    }

    private fun JSONObject.optIntOrNull(key: String): Int? =
        if (!has(key) || isNull(key)) null else optInt(key)

    /** Exposed for unit tests: build a body without performing I/O. */
    fun buildBodyForTest(request: ChatRequest): JSONObject = buildBody(request)
}
