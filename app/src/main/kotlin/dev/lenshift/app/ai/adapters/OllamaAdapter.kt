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
 * Ollama `/api/chat` adapter for fully local inference.
 *
 * No API key is sent. The default base URL a user should use from an emulator is
 * `http://10.0.2.2:11434` (the host loopback alias), which is why plain HTTP is
 * permitted for loopback/private hosts when the profile opts in.
 *
 * Streaming is newline-delimited JSON, not SSE.
 */
class OllamaAdapter : BaseAdapter() {

    override val protocol = ApiProtocol.OLLAMA

    override fun endpoint(profile: ProviderProfile): String =
        "${profile.normalizedBase()}/api/chat"

    override fun stream(request: ChatRequest): Flow<StreamEvent> =
        ndjsonPost(
            profile = request.profile,
            url = endpoint(request.profile),
            apiKey = null,
            scheme = HttpSupport.AuthScheme.NONE,
            body = buildBody(request),
            transform = ::parseLine
        )

    private fun buildBody(request: ChatRequest): JSONObject = run {
        val profile = request.profile
        val body = JSONObject()
        body.put("model", profile.modelId)
        body.put("stream", profile.streaming)
        body.put("messages", buildMessages(request))

        val options = JSONObject()
        val capabilities = request.capabilities
        if (capabilities.accepts(ModelSpec.TEMPERATURE)) profile.temperature?.let { options.put("temperature", it.toDouble()) }
        if (capabilities.accepts(ModelSpec.TOP_P)) profile.topP?.let { options.put("top_p", it.toDouble()) }
        if (capabilities.accepts(ModelSpec.TOP_K)) profile.topK?.let { options.put("top_k", it) }
        profile.maxOutputTokens?.let { options.put("num_predict", it) }
        if (options.length() > 0) body.put("options", options)

        ReasoningMapper.applyOllama(body, request.reasoningMode, request.capabilities, request.profile.customReasoning.selected)

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

        request.messages.forEach { message ->
            val role = when (message.role) {
                ChatMessage.Role.SYSTEM -> "system"
                ChatMessage.Role.USER -> "user"
                ChatMessage.Role.ASSISTANT -> "assistant"
            }
            val entry = JSONObject().put("role", role).put("content", message.text)

            if (message.images.isNotEmpty() && visionAllowed && message.role == ChatMessage.Role.USER) {
                // Ollama accepts bare base64 strings in `images`.
                val images = JSONArray()
                message.images.forEach { image ->
                    images.put(
                        ImageEncoder.encode(
                            bitmap = image,
                            maxEdgePx = request.profile.maxImageEdgePx,
                            jpegQuality = request.profile.jpegQuality,
                            detail = request.profile.imageDetail
                        ).base64
                    )
                }
                entry.put("images", images)
            }
            array.put(entry)
        }
        return array
    }

    private fun parseLine(line: String): StreamEvent? {
        val json = runCatching { JSONObject(line) }.getOrNull() ?: return null

        json.optString("error").takeIf { it.isNotBlank() }?.let {
            throw AiError.Server(200, it)
        }

        val message = json.optJSONObject("message")
        val thinking = message?.optString("thinking").orEmpty()
        if (thinking.isNotEmpty()) return StreamEvent.ReasoningDelta(thinking)

        val content = message?.optString("content").orEmpty()
        if (content.isNotEmpty()) return StreamEvent.TextDelta(content)

        if (json.optBoolean("done", false)) {
            val input = json.optInt("prompt_eval_count").takeIf { json.has("prompt_eval_count") }
            val output = json.optInt("eval_count").takeIf { json.has("eval_count") }
            if (input != null || output != null) {
                return StreamEvent.Usage(input, output, null)
            }
        }
        return null
    }

    override suspend fun listModels(
        profile: ProviderProfile,
        apiKey: String?
    ): List<ModelInfo>? = withContext(Dispatchers.IO) {
        val names = getBody(profile, "${profile.normalizedBase()}/api/tags", null, HttpSupport.AuthScheme.NONE)
            ?.let(ModelListParser::ollamaTags) ?: return@withContext null
        // Local models are few; /api/show reports each one's context and capabilities.
        names.mapIndexed { index, name ->
            val spec = if (index < MAX_DESCRIBED) describe(profile, name) else null
            ModelInfo(name, spec = spec)
        }
    }

    private suspend fun describe(profile: ProviderProfile, name: String): ModelSpec? = runCatching {
        post(profile, "${profile.normalizedBase()}/api/show", null, HttpSupport.AuthScheme.NONE,
            JSONObject().put("model", name)) { response -> response.body?.string() }
    }.getOrNull()?.let(ModelListParser::ollamaShow)

    private companion object {
        /** /api/show is one request per model; enough for any local install. */
        const val MAX_DESCRIBED = 40
    }

    override suspend fun testConnection(
        profile: ProviderProfile,
        apiKey: String?
    ): ConnectionTest {
        // Ollama may have no model pulled yet; listing tags is a valid connectivity test.
        val started = System.currentTimeMillis()
        val models = listModels(profile, null)
        return if (models != null) {
            ConnectionTest(
                ok = true,
                headline = "Connected",
                detail = if (models.isEmpty()) {
                    "Ollama is running but no models are pulled yet. Run `ollama pull <model>`."
                } else {
                    "${models.size} local model(s) available"
                },
                latencyMs = System.currentTimeMillis() - started,
                models = models.map { it.id }
            )
        } else {
            probe(profile, null)
        }
    }

    fun buildBodyForTest(request: ChatRequest): JSONObject = buildBody(request)
}
