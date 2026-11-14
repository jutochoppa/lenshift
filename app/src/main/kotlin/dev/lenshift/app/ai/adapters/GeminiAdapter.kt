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
import dev.lenshift.app.ai.StreamEvent
import dev.lenshift.app.ai.transport.HttpSupport
import dev.lenshift.app.ai.transport.ImageEncoder
import org.json.JSONArray
import org.json.JSONObject

/**
 * Native Gemini `generateContent` adapter.
 *
 * Shape notes:
 *  - The model goes in the **path**: `/models/{model}:generateContent`.
 *  - Roles are `user` and `model` (not `assistant`).
 *  - Images are `inline_data` parts with base64 payloads.
 *  - The system prompt is a separate `systemInstruction` object.
 *  - Streaming requires `?alt=sse`; without it the API returns a JSON array of chunks.
 *  - Thinking is `generationConfig.thinkingConfig` (see [ReasoningMapper]).
 *
 * The model id is used verbatim, so `models/gemini-2.5-pro` and `gemini-2.5-pro` both
 * work, as does a tuned-model path such as `tunedModels/my-model`.
 */
class GeminiAdapter : BaseAdapter() {

    override val protocol = ApiProtocol.GEMINI

    override fun endpoint(profile: ProviderProfile): String {
        val model = profile.modelId.trim().removePrefix("models/")
        val base = profile.normalizedBase()
        val verb = if (profile.streaming) "streamGenerateContent?alt=sse" else "generateContent"
        return "$base/models/$model:$verb"
    }

    override fun stream(request: ChatRequest): Flow<StreamEvent> =
        streamingPost(
            profile = request.profile,
            url = endpoint(request.profile),
            apiKey = request.apiKey,
            scheme = HttpSupport.AuthScheme.X_GOOG_API_KEY,
            body = buildBody(request),
            transform = ::parseChunk
        )

    private fun buildBody(request: ChatRequest): JSONObject = run {
        val profile = request.profile
        val body = JSONObject()

        val systemText = request.messages
            .filter { it.role == ChatMessage.Role.SYSTEM }
            .joinToString("\n\n") { it.text }
            .trim()
        if (systemText.isNotEmpty()) {
            body.put(
                "systemInstruction",
                JSONObject().put(
                    "parts",
                    JSONArray().put(JSONObject().put("text", systemText))
                )
            )
        }

        body.put("contents", buildContents(request))

        val generationConfig = JSONObject()
        val capabilities = request.capabilities
        if (capabilities.accepts(ModelSpec.TEMPERATURE)) {
            profile.temperature?.let { generationConfig.put("temperature", it.toDouble()) }
        }
        if (capabilities.accepts(ModelSpec.TOP_P)) profile.topP?.let { generationConfig.put("topP", it.toDouble()) }
        if (capabilities.accepts(ModelSpec.TOP_K)) profile.topK?.let { generationConfig.put("topK", it) }
        profile.maxOutputTokens?.let { generationConfig.put("maxOutputTokens", it) }

        ReasoningMapper.applyGemini(
            generationConfig = generationConfig,
            mode = request.reasoningMode,
            capabilities = request.capabilities,
            explicitBudget = profile.reasoningTokenBudget,
            custom = profile.customReasoning.selected
        )

        if (generationConfig.length() > 0) body.put("generationConfig", generationConfig)

        if (profile.extraJsonParameters.isNotBlank()) {
            runCatching {
                val extra = JSONObject(profile.extraJsonParameters)
                extra.keys().forEach { key -> body.put(key, extra.get(key)) }
            }
        }
        body
    }

    private fun buildContents(request: ChatRequest): JSONArray {
        val contents = JSONArray()
        val visionAllowed = request.capabilities.vision != dev.lenshift.app.ai.Tri.NO

        request.messages
            .filter { it.role != ChatMessage.Role.SYSTEM }
            .forEach { message ->
                val role = if (message.role == ChatMessage.Role.ASSISTANT) "model" else "user"
                val parts = JSONArray()

                if (message.text.isNotBlank()) {
                    parts.put(JSONObject().put("text", message.text))
                }

                if (visionAllowed && role == "user") message.images.forEach { image ->
                    val encoded = ImageEncoder.encode(
                        bitmap = image,
                        maxEdgePx = request.profile.maxImageEdgePx,
                        jpegQuality = request.profile.jpegQuality,
                        detail = request.profile.imageDetail
                    )
                    parts.put(
                        JSONObject().put(
                            "inline_data",
                            JSONObject()
                                .put("mime_type", encoded.mimeType)
                                .put("data", encoded.base64)
                        )
                    )
                }

                if (parts.length() > 0) {
                    contents.put(JSONObject().put("role", role).put("parts", parts))
                }
            }
        return contents
    }

    private fun parseChunk(payload: String): StreamEvent? {
        val trimmed = payload.trim()
        if (trimmed.isEmpty()) return null

        // Non-streaming responses come back as a JSON array of candidates.
        if (trimmed.startsWith("[")) {
            val array = runCatching { JSONArray(trimmed) }.getOrNull() ?: return null
            val sb = StringBuilder()
            for (i in 0 until array.length()) {
                val text = array.optJSONObject(i)?.let { extractText(it) }
                if (!text.isNullOrEmpty()) sb.append(text)
            }
            return if (sb.isNotEmpty()) StreamEvent.TextDelta(sb.toString()) else null
        }

        val json = runCatching { JSONObject(trimmed) }.getOrNull() ?: return null

        json.optJSONObject("error")?.let { error ->
            throw AiError.Server(200, error.optString("message").ifBlank { error.toString() })
        }

        // Thought summaries arrive as parts flagged `thought: true`.
        val candidates = json.optJSONArray("candidates") ?: return usageOnly(json)
        if (candidates.length() == 0) return usageOnly(json)

        val candidate = candidates.optJSONObject(0) ?: return null
        val parts = candidate.optJSONObject("content")?.optJSONArray("parts") ?: return null

        val reasoning = StringBuilder()
        val text = StringBuilder()
        for (i in 0 until parts.length()) {
            val part = parts.optJSONObject(i) ?: continue
            val value = part.optString("text")
            if (value.isEmpty()) continue
            if (part.optBoolean("thought", false)) reasoning.append(value) else text.append(value)
        }

        return when {
            reasoning.isNotEmpty() -> StreamEvent.ReasoningDelta(reasoning.toString())
            text.isNotEmpty() -> StreamEvent.TextDelta(text.toString())
            else -> null
        }
    }

    private fun extractText(json: JSONObject): String? {
        val parts = json.optJSONArray("candidates")
            ?.optJSONObject(0)
            ?.optJSONObject("content")
            ?.optJSONArray("parts") ?: return null
        val sb = StringBuilder()
        for (i in 0 until parts.length()) {
            val part = parts.optJSONObject(i) ?: continue
            if (part.optBoolean("thought", false)) continue
            sb.append(part.optString("text"))
        }
        return sb.toString().ifEmpty { null }
    }

    private fun usageOnly(json: JSONObject): StreamEvent? {
        val usage = json.optJSONObject("usageMetadata") ?: return null
        return StreamEvent.Usage(
            inputTokens = usage.optInt("promptTokenCount").takeIf { usage.has("promptTokenCount") },
            outputTokens = usage.optInt("candidatesTokenCount")
                .takeIf { usage.has("candidatesTokenCount") },
            reasoningTokens = usage.optInt("thoughtsTokenCount")
                .takeIf { usage.has("thoughtsTokenCount") }
        )
    }

    override suspend fun listModels(
        profile: ProviderProfile,
        apiKey: String?
    ): List<ModelInfo>? = withContext(Dispatchers.IO) {
        getBody(profile, "${profile.normalizedBase()}/models?pageSize=1000", apiKey, HttpSupport.AuthScheme.X_GOOG_API_KEY)
            ?.let(ModelListParser::gemini)
    }

    override suspend fun testConnection(
        profile: ProviderProfile,
        apiKey: String?
    ): ConnectionTest = probe(profile, apiKey)

    fun buildBodyForTest(request: ChatRequest): JSONObject = buildBody(request)
}
