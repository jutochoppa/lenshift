package dev.lenshift.app.ai

import dev.lenshift.app.ai.transport.CallRegistry
import dev.lenshift.app.ai.transport.HttpSupport
import dev.lenshift.app.ai.transport.ProviderLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

/** A picture an answer asked for; [inputs] are images to edit or restyle. */
data class ImageRequest(val prompt: String, val aspect: String? = null, val inputs: List<EncodedImage> = emptyList())

class EncodedImage(val base64: String, val mimeType: String) {
    val dataUrl: String get() = "data:$mimeType;base64,$base64"
}

/**
 * Asks an image-capable model for a picture. Three routes cover the providers Lenshift
 * speaks to: chat completions with `modalities: ["image", "text"]` (OpenRouter and
 * compatible gateways), OpenAI's Images API (gpt-image, DALL·E) and Gemini's
 * generateContent with image output. Bodies and replies are handled by pure functions,
 * so each route is unit-tested.
 */
object ImageGeneration {

    enum class Route { CHAT, IMAGES_API, GEMINI }

    /** A picture in a reply: its bytes, or a URL to fetch them from. */
    sealed interface ImageData {
        class Bytes(val data: ByteArray) : ImageData
        class Url(val url: String) : ImageData
    }

    class Parsed(val images: List<ImageData>, val text: String?)

    private const val MAX_DOWNLOAD = 25L * 1024 * 1024
    private val JSON = "application/json; charset=utf-8".toMediaType()

    fun route(profile: ProviderProfile): Route? = when (profile.protocol) {
        ApiProtocol.GEMINI -> Route.GEMINI
        ApiProtocol.OPENAI_CHAT, ApiProtocol.OPENAI_RESPONSES ->
            if (!isOpenRouter(profile) && isImagesApiModel(profile.modelId)) Route.IMAGES_API else Route.CHAT
        else -> null
    }

    private fun isOpenRouter(profile: ProviderProfile) = profile.normalizedBase().contains("openrouter.ai", ignoreCase = true)

    /** gpt-image and DALL·E only work through the Images API. */
    fun isImagesApiModel(model: String): Boolean = KnownModels.normalize(model).let { it.startsWith("gpt-image") || it.startsWith("dall-e") }

    fun url(profile: ProviderProfile, route: Route): String = when (route) {
        Route.CHAT -> "${profile.normalizedBase()}/chat/completions"
        Route.IMAGES_API -> "${profile.normalizedBase()}/images/generations"
        Route.GEMINI -> "${profile.normalizedBase()}/models/${profile.modelId.trim().removePrefix("models/")}:generateContent"
    }

    fun chatBody(model: String, request: ImageRequest, openRouter: Boolean): JSONObject {
        val content: Any = if (request.inputs.isEmpty()) {
            request.prompt
        } else {
            JSONArray().apply {
                put(JSONObject().put("type", "text").put("text", request.prompt))
                request.inputs.forEach { image ->
                    put(JSONObject().put("type", "image_url").put("image_url", JSONObject().put("url", image.dataUrl)))
                }
            }
        }
        return JSONObject().apply {
            put("model", model)
            put("stream", false)
            put("modalities", JSONArray().put("image").put("text"))
            put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", content)))
            if (openRouter && request.aspect != null) put("image_config", JSONObject().put("aspect_ratio", request.aspect))
        }
    }

    fun imagesBody(model: String, request: ImageRequest): JSONObject {
        val name = KnownModels.normalize(model)
        return JSONObject().apply {
            put("model", model)
            put("prompt", request.prompt)
            put("n", 1)
            put("size", size(model, request.aspect))
            // gpt-image always returns base64 and rejects this field.
            if (name.startsWith("dall-e")) put("response_format", "b64_json")
        }
    }

    /** The Images API size closest to [aspect] that [model] offers. */
    private fun size(model: String, aspect: String?): String {
        val name = KnownModels.normalize(model)
        val shape = shape(aspect)
        return when {
            name.startsWith("dall-e-2") -> "1024x1024"
            name.startsWith("dall-e") -> when (shape) {
                1 -> "1792x1024"
                -1 -> "1024x1792"
                else -> "1024x1024"
            }
            else -> when (shape) {
                1 -> "1536x1024"
                -1 -> "1024x1536"
                else -> "1024x1024"
            }
        }
    }

    /**
     * An edit of [ImageRequest.inputs] through the Images API's `/images/edits`: a form with
     * the prompt and each picture as a file (`image`, or `image[]` for several).
     */
    fun editsBody(model: String, request: ImageRequest): MultipartBody {
        val form = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("model", model)
            .addFormDataPart("prompt", request.prompt)
            .addFormDataPart("n", "1")
            .addFormDataPart("size", size(model, request.aspect))
        val field = if (request.inputs.size == 1) "image" else "image[]"
        request.inputs.forEachIndexed { i, image ->
            val bytes = java.util.Base64.getMimeDecoder().decode(image.base64)
            val extension = if (image.mimeType.contains("png")) "png" else "jpg"
            form.addFormDataPart(field, "image-${i + 1}.$extension", bytes.toRequestBody(image.mimeType.toMediaType()))
        }
        return form.build()
    }

    fun editsUrl(profile: ProviderProfile): String = "${profile.normalizedBase()}/images/edits"

    fun geminiBody(request: ImageRequest): JSONObject {
        val parts = JSONArray().put(JSONObject().put("text", request.prompt))
        request.inputs.forEach { image ->
            parts.put(JSONObject().put("inlineData", JSONObject().put("mimeType", image.mimeType).put("data", image.base64)))
        }
        val config = JSONObject().put("responseModalities", JSONArray().put("TEXT").put("IMAGE"))
        request.aspect?.let { config.put("imageConfig", JSONObject().put("aspectRatio", it)) }
        return JSONObject()
            .put("contents", JSONArray().put(JSONObject().put("role", "user").put("parts", parts)))
            .put("generationConfig", config)
    }

    /** 1 for landscape, −1 for portrait, 0 for square or unknown. */
    private fun shape(aspect: String?): Int {
        val p = aspect?.split(':')?.mapNotNull { it.trim().toDoubleOrNull() }?.takeIf { it.size == 2 && it[1] > 0 } ?: return 0
        val r = p[0] / p[1]
        return when {
            r > 1.15 -> 1
            r < 0.87 -> -1
            else -> 0
        }
    }

    /** Images in a chat completion: `message.images[]`, or image parts of `message.content`. */
    fun parseChat(raw: String): Parsed {
        val message = JSONObject(raw).optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")
            ?: return Parsed(emptyList(), null)
        val images = mutableListOf<ImageData>()
        val text = StringBuilder()
        message.optJSONArray("images")?.let { arr ->
            for (i in 0 until arr.length()) imageFrom(arr.opt(i))?.let { images += it }
        }
        when (val content = message.opt("content")) {
            is String -> text.append(content)
            is JSONArray -> for (i in 0 until content.length()) {
                val part = content.optJSONObject(i) ?: continue
                when (part.optString("type")) {
                    "text", "output_text" -> text.append(part.optString("text"))
                    else -> imageFrom(part)?.let { images += it }
                }
            }
        }
        return Parsed(images, text.toString().trim().ifEmpty { null })
    }

    /** One image entry: `{image_url: {url}}`, `{image_url: "…"}`, `{b64_json}` or a bare URL. */
    private fun imageFrom(raw: Any?): ImageData? {
        val url = when (raw) {
            is String -> raw
            is JSONObject -> {
                raw.optString("b64_json").takeIf { it.isNotEmpty() }?.let { return bytes(it) }
                when (val u = raw.opt("image_url") ?: raw.opt("imageUrl")) {
                    is JSONObject -> u.optString("url")
                    is String -> u
                    else -> raw.optString("url")
                }
            }
            else -> null
        }?.takeIf { it.isNotBlank() } ?: return null
        return if (url.startsWith("data:")) decodeDataUrl(url)?.let { ImageData.Bytes(it) } else ImageData.Url(url)
    }

    fun parseImages(raw: String): Parsed {
        val data = JSONObject(raw).optJSONArray("data") ?: return Parsed(emptyList(), null)
        val images = (0 until data.length()).mapNotNull { i ->
            val item = data.optJSONObject(i) ?: return@mapNotNull null
            item.optString("b64_json").takeIf { it.isNotEmpty() }?.let { bytes(it) }
                ?: item.optString("url").takeIf { it.isNotEmpty() }?.let { ImageData.Url(it) }
        }
        return Parsed(images, data.optJSONObject(0)?.optString("revised_prompt")?.ifEmpty { null })
    }

    fun parseGemini(raw: String): Parsed {
        val parts = JSONObject(raw).optJSONArray("candidates")?.optJSONObject(0)?.optJSONObject("content")?.optJSONArray("parts")
            ?: return Parsed(emptyList(), null)
        val images = mutableListOf<ImageData>()
        val text = StringBuilder()
        for (i in 0 until parts.length()) {
            val part = parts.optJSONObject(i) ?: continue
            val inline = part.optJSONObject("inlineData") ?: part.optJSONObject("inline_data")
            val data = inline?.optString("data").orEmpty()
            if (data.isNotEmpty()) bytes(data)?.let { images += it } else if (!part.optBoolean("thought")) text.append(part.optString("text"))
        }
        return Parsed(images, text.toString().trim().ifEmpty { null })
    }

    /** The bytes of a `data:image/…;base64,…` URL. */
    fun decodeDataUrl(url: String): ByteArray? {
        val comma = url.indexOf(',')
        if (comma < 0 || !url.substring(0, comma).contains("base64")) return null
        return runCatching { java.util.Base64.getMimeDecoder().decode(url.substring(comma + 1)) }.getOrNull()
    }

    private fun bytes(base64: String): ImageData.Bytes? =
        runCatching { java.util.Base64.getMimeDecoder().decode(base64) }.getOrNull()?.let { ImageData.Bytes(it) }

    /**
     * Makes the picture with [profile]'s current model and returns its encoded bytes. The
     * call is tagged with [callKey], so stopping the chat leaves it running and
     * [CallRegistry.cancelBackground] can end it.
     */
    suspend fun generate(profile: ProviderProfile, apiKey: String?, request: ImageRequest, callKey: String): List<ByteArray> =
        withContext(Dispatchers.IO) {
            val route = route(profile) ?: throw AiError.Protocol("${profile.protocol.label} can't create images")
            // Editing a picture with gpt-image or DALL·E is a separate, multipart endpoint.
            val edit = route == Route.IMAGES_API && request.inputs.isNotEmpty()
            val (scheme, body) = when (route) {
                Route.CHAT -> HttpSupport.AuthScheme.BEARER to chatBody(profile.modelId, request, isOpenRouter(profile))
                Route.IMAGES_API -> HttpSupport.AuthScheme.BEARER to if (edit) {
                    // What the log records of the form; the pictures themselves stay out.
                    JSONObject().put("model", profile.modelId).put("prompt", request.prompt)
                        .put("images", request.inputs.size).put("size", size(profile.modelId, request.aspect))
                } else {
                    imagesBody(profile.modelId, request)
                }
                Route.GEMINI -> HttpSupport.AuthScheme.X_GOOG_API_KEY to geminiBody(request)
            }
            val endpoint = if (edit) editsUrl(profile) else url(profile, route)
            val builder = HttpSupport.requestBuilder(profile, endpoint, apiKey, scheme)
                .tag(CallRegistry.Background::class.java, CallRegistry.Background(callKey))
            val call = if (edit) {
                builder.post(editsBody(profile.modelId, request)).build()
            } else {
                builder.header("Content-Type", "application/json").post(body.toString().toRequestBody(JSON)).build()
            }
            val raw = HttpSupport.execute(profile, call).use { it.body?.string().orEmpty() }
            val parsed = runCatching {
                when (route) {
                    Route.CHAT -> parseChat(raw)
                    Route.IMAGES_API -> parseImages(raw)
                    Route.GEMINI -> parseGemini(raw)
                }
            }.getOrElse {
                ProviderLog.unreadable(profile, endpoint, apiKey, null, raw, "the image reply could not be parsed", body)
                throw AiError.Protocol("The image model's reply could not be read.")
            }
            val images = parsed.images.mapNotNull { image ->
                when (image) {
                    is ImageData.Bytes -> image.data
                    is ImageData.Url -> download(profile, image.url, callKey)
                }
            }
            if (images.isEmpty()) {
                ProviderLog.unreadable(profile, endpoint, apiKey, null, raw, "no picture in the reply", body)
                throw AiError.Protocol(
                    parsed.text?.let { "The model answered without a picture: ${it.take(160)}" } ?: "The model returned no picture."
                )
            }
            images
        }

    /** Fetches a picture the provider links to; no key or custom header goes to that host. */
    private fun download(profile: ProviderProfile, url: String, callKey: String): ByteArray? {
        if (!url.startsWith("https://")) return null
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "Lenshift/1.0 (Android)")
            .tag(CallRegistry.Background::class.java, CallRegistry.Background(callKey))
            .get()
            .build()
        return HttpSupport.execute(profile, request).use { response ->
            val body = response.body ?: return@use null
            if (body.contentLength() > MAX_DOWNLOAD) return@use null
            body.bytes().takeIf { it.size <= MAX_DOWNLOAD }
        }
    }
}
