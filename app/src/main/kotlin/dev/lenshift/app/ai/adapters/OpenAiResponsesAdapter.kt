package dev.lenshift.app.ai.adapters

import dev.lenshift.app.ai.ModelInfo
import dev.lenshift.app.ai.ModelListParser
import dev.lenshift.app.ai.ModelSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.Flow
import dev.lenshift.app.ai.ApiProtocol
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
 * [OI] Responses API adapter.
 *
 * This is a genuinely different protocol from Chat Completions, not a rename:
 *
 *  - The body uses `input` (items or a string) and `instructions`, not `messages`.
 *  - The output budget is `max_output_tokens`, not `max_tokens`.
 *  - Reasoning is `reasoning: { effort, summary }`.
 *  - Multi-turn state is server-side: `previous_response_id` continues a conversation,
 *    and for stateless reasoning continuation the caller must replay the encrypted
 *    reasoning items. We therefore request `include: ["reasoning.encrypted_content"]`
 *    and echo them back, and we also keep the response id.
 *  - Streaming uses named events (`response.output_text.delta`,
 *    `response.reasoning_summary_text.delta`, `response.completed`, ...) rather than
 *    chat-completion deltas.
 *
 * `store` is sent as `false` so the provider keeps no server-side copy of the user's
 * screen content. That is a deliberate privacy default; it means continuation relies on
 * replaying items rather than on server state alone.
 */
class OpenAiResponsesAdapter : BaseAdapter() {

    override val protocol = ApiProtocol.OPENAI_RESPONSES

    override fun endpoint(profile: ProviderProfile): String =
        "${profile.normalizedBase()}/responses"

    @Volatile
    private var lastResponseId: String? = null

    override fun continuationFromStream(): String? = lastResponseId

    override fun stream(request: ChatRequest): Flow<StreamEvent> {
        lastResponseId = null
        return streamingPost(
            profile = request.profile,
            url = endpoint(request.profile),
            apiKey = request.apiKey,
            scheme = HttpSupport.AuthScheme.BEARER,
            body = buildBody(request),
            transform = ::parseEvent
        )
    }

    private fun buildBody(request: ChatRequest): JSONObject = run {
        val profile = request.profile
        val body = JSONObject()
        body.put("model", profile.modelId)
        body.put("stream", profile.streaming)

        // Privacy: do not retain the conversation on the provider side.
        body.put("store", false)

        // Reasoning models need encrypted reasoning items to continue statelessly.
        body.put("include", JSONArray().put("reasoning.encrypted_content"))

        val systemText = request.messages
            .filter { it.role == ChatMessage.Role.SYSTEM }
            .joinToString("\n\n") { it.text }
            .trim()
        if (systemText.isNotEmpty()) body.put("instructions", systemText)

        body.put("input", buildInput(request))

        profile.maxOutputTokens?.let { body.put("max_output_tokens", it) }
        if (request.capabilities.accepts(ModelSpec.TEMPERATURE)) {
            profile.temperature?.let { body.put("temperature", it.toDouble()) }
        }
        if (request.capabilities.accepts(ModelSpec.TOP_P)) profile.topP?.let { body.put("top_p", it.toDouble()) }

        ReasoningMapper.applyOpenAiResponses(body, request.reasoningMode, request.capabilities)

        if (profile.reasoningMode != dev.lenshift.app.ai.ReasoningMode.AUTO &&
            request.reasoningMode != dev.lenshift.app.ai.ReasoningMode.AUTO
        ) {
            body.put("reasoning_summary", "auto")
        }

        request.continuationToken?.let { body.put("previous_response_id", it) }

        if (profile.extraJsonParameters.isNotBlank()) {
            runCatching {
                val extra = JSONObject(profile.extraJsonParameters)
                extra.keys().forEach { key -> body.put(key, extra.get(key)) }
            }
        }
        body
    }

    private fun buildInput(request: ChatRequest): JSONArray {
        val items = JSONArray()
        val visionAllowed = request.capabilities.vision != dev.lenshift.app.ai.Tri.NO

        request.messages
            .filter { it.role != ChatMessage.Role.SYSTEM }
            .forEach { message ->
                val role = when (message.role) {
                    ChatMessage.Role.USER -> "user"
                    ChatMessage.Role.ASSISTANT -> "assistant"
                    ChatMessage.Role.SYSTEM -> return@forEach
                }

                if (message.images.isNotEmpty() && visionAllowed && message.role == ChatMessage.Role.USER) {
                    val content = JSONArray()
                    if (message.text.isNotBlank()) {
                        content.put(
                            JSONObject()
                                .put("type", "input_text")
                                .put("text", message.text)
                        )
                    }
                    message.images.forEach { image ->
                        val encoded = ImageEncoder.encode(
                        bitmap = image,
                        maxEdgePx = request.profile.maxImageEdgePx,
                        jpegQuality = request.profile.jpegQuality,
                        detail = request.profile.imageDetail
                    )
                        content.put(
                            JSONObject()
                                .put("type", "input_image")
                                .put("image_url", "data:${encoded.mimeType};base64,${encoded.base64}")
                                .put("detail", request.profile.imageDetail.id)
                        )
                    }
                    items.put(
                        JSONObject().put("role", role).put("content", content)
                    )
                } else {
                    items.put(
                        JSONObject()
                            .put("role", role)
                            .put(
                                "content",
                                JSONArray().put(
                                    JSONObject()
                                        .put("type", "input_text")
                                        .put("text", message.text)
                                )
                            )
                    )
                }
            }
        return items
    }

    private fun parseEvent(payload: String): StreamEvent? {
        val trimmed = payload.trim()
        if (trimmed.isEmpty()) return null
        val json = runCatching { JSONObject(trimmed) }.getOrNull() ?: return null

        return when (val type = json.optString("type")) {
            "response.output_text.delta" ->
                json.optString("delta").takeIf { it.isNotEmpty() }
                    ?.let { StreamEvent.TextDelta(it) }

            "response.reasoning_summary_text.delta",
            "response.reasoning_text.delta" ->
                json.optString("delta").takeIf { it.isNotEmpty() }
                    ?.let { StreamEvent.ReasoningDelta(it) }

            "response.completed" -> {
                val response = json.optJSONObject("response")
                lastResponseId = response?.optString("id")?.takeIf { it.isNotBlank() }
                response?.optJSONObject("usage")?.let { usage ->
                    StreamEvent.Usage(
                        inputTokens = usage.optInt("input_tokens").takeIf { usage.has("input_tokens") },
                        outputTokens = usage.optInt("output_tokens").takeIf { usage.has("output_tokens") },
                        reasoningTokens = usage.optJSONObject("output_tokens_details")
                            ?.optInt("reasoning_tokens")
                    )
                }
            }

            "response.failed", "error" -> {
                val message = json.optJSONObject("response")
                    ?.optJSONObject("error")
                    ?.optString("message")
                    ?: json.optJSONObject("error")?.optString("message")
                    ?: json.optString("message")
                    ?: "The provider reported a failure"
                throw dev.lenshift.app.ai.AiError.Server(200, message)
            }

            "response.incomplete" -> {
                val reason = json.optJSONObject("response")
                    ?.optString("incomplete_details")
                StreamEvent.Notice(
                    "Response incomplete${if (reason.isNullOrBlank()) "" else ": $reason"}"
                )
            }

            else -> null
        }
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

    fun buildBodyForTest(request: ChatRequest): JSONObject = buildBody(request)
}
