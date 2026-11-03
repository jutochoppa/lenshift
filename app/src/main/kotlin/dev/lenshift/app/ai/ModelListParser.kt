package dev.lenshift.app.ai

import org.json.JSONArray
import org.json.JSONObject

/**
 * Reads model lists, keeping whatever limits and capabilities each provider reports.
 *
 * Field names differ by provider, so each is read only where that provider documents
 * it: OpenRouter (`context_length`, `top_provider.max_completion_tokens`,
 * `architecture.input_modalities`, `supported_parameters`), Groq (`context_window`),
 * Mistral (`max_context_length`, `capabilities.vision`), vLLM (`max_model_len`),
 * Gemini (`inputTokenLimit`, `outputTokenLimit`, `thinking`) and Ollama (`/api/show`).
 * Pure functions, so the parsing is unit tested without a network.
 */
object ModelListParser {

    /** OpenAI-style `{data: [...]}` lists, or the bare array some gateways return. */
    fun openAiStyle(raw: String): List<ModelInfo>? {
        val trimmed = raw.trim()
        val data = when {
            trimmed.startsWith("[") -> runCatching { JSONArray(trimmed) }.getOrNull()
            else -> runCatching { JSONObject(trimmed) }.getOrNull()?.let {
                it.optJSONArray("data") ?: it.optJSONArray("models")
            }
        } ?: return null
        return items(data).mapNotNull(::openAiItem).sortedBy { it.id }
    }

    fun openAiItem(item: JSONObject): ModelInfo? {
        val id = item.optString("id").ifBlank { item.optString("name") }.trim()
        if (id.isEmpty()) return null
        val top = item.optJSONObject("top_provider")
        val architecture = item.optJSONObject("architecture")
        val capabilities = item.optJSONObject("capabilities")
        val supported = item.optJSONArray("supported_parameters")?.let(::strings)?.toSet()

        val inputs = architecture?.optJSONArray("input_modalities")?.let(::strings)
        val vision = when {
            inputs != null -> Tri.of("image" in inputs)
            architecture?.optString("modality").orEmpty().contains("->") ->
                Tri.of(architecture!!.optString("modality").substringBefore("->").contains("image"))
            capabilities?.has("vision") == true -> Tri.of(capabilities.optBoolean("vision"))
            else -> Tri.UNKNOWN
        }
        val outputs = architecture?.optJSONArray("output_modalities")?.let(::strings)
        val reasoning = when {
            supported != null -> Tri.of("reasoning" in supported || "include_reasoning" in supported)
            else -> Tri.UNKNOWN
        }
        val spec = ModelSpec(
            contextWindow = item.positive("context_length") ?: top?.positive("context_length")
                ?: item.positive("context_window") ?: item.positive("max_context_length")
                ?: item.positive("max_model_len") ?: item.positive("max_input_tokens"),
            maxOutputTokens = top?.positive("max_completion_tokens")
                ?: item.positive("max_completion_tokens") ?: item.positive("max_output_tokens"),
            vision = vision,
            reasoning = reasoning,
            parameters = supported,
            imageOutput = outputs?.let { Tri.of("image" in it) } ?: Tri.UNKNOWN,
            textOutput = outputs?.let { Tri.of("text" in it) } ?: Tri.UNKNOWN
        )
        val name = item.optString("name").trim().takeIf { it.isNotEmpty() && it != id }
            ?: item.optString("display_name").trim().takeIf { it.isNotEmpty() && it != id }
        return ModelInfo(id, name, spec.takeUnless { it.isEmpty })
    }

    /** Gemini `{models: [...]}`, keeping only models that generate content. */
    fun gemini(raw: String): List<ModelInfo>? {
        val models = runCatching { JSONObject(raw) }.getOrNull()?.optJSONArray("models") ?: return null
        return items(models).mapNotNull { item ->
            val methods = item.optJSONArray("supportedGenerationMethods")?.let(::strings)
            if (methods != null && "generateContent" !in methods) return@mapNotNull null
            val id = item.optString("name").removePrefix("models/").trim()
            if (id.isEmpty()) return@mapNotNull null
            val thinking = if (item.has("thinking")) Tri.of(item.optBoolean("thinking")) else Tri.UNKNOWN
            val parameters = buildSet {
                add(ModelSpec.TEMPERATURE)
                add(ModelSpec.TOP_P)
                add(ModelSpec.MAX_TOKENS)
                if (item.has("topK")) add(ModelSpec.TOP_K)
                if (thinking == Tri.YES) add(ModelSpec.REASONING)
            }
            val spec = ModelSpec(
                contextWindow = item.positive("inputTokenLimit"),
                maxOutputTokens = item.positive("outputTokenLimit"),
                reasoning = thinking,
                // Without a thinking flag the list says nothing about reasoning.
                parameters = parameters.takeIf { item.has("thinking") }
            )
            ModelInfo(id, item.optString("displayName").trim().takeIf { it.isNotEmpty() }, spec.takeUnless { it.isEmpty })
        }.sortedBy { it.id }
    }

    /** Anthropic `{data: [...]}`; limits are read only if the response carries them. */
    fun anthropic(raw: String): List<ModelInfo>? {
        val data = runCatching { JSONObject(raw) }.getOrNull()?.optJSONArray("data") ?: return null
        return items(data).mapNotNull { item ->
            val id = item.optString("id").trim()
            if (id.isEmpty()) return@mapNotNull null
            val spec = ModelSpec(
                contextWindow = item.positive("max_input_tokens") ?: item.positive("context_window"),
                maxOutputTokens = item.positive("max_output_tokens") ?: item.positive("max_tokens")
            )
            ModelInfo(id, item.optString("display_name").trim().takeIf { it.isNotEmpty() }, spec.takeUnless { it.isEmpty })
        }.sortedBy { it.id }
    }

    /** Ollama `/api/tags` names. */
    fun ollamaTags(raw: String): List<String>? {
        val models = runCatching { JSONObject(raw) }.getOrNull()?.optJSONArray("models") ?: return null
        return items(models).mapNotNull { it.optString("name").trim().takeIf(String::isNotEmpty) }.sorted()
    }

    /** Ollama `/api/show`: context from `model_info`, vision and thinking from `capabilities`. */
    fun ollamaShow(raw: String): ModelSpec? {
        val json = runCatching { JSONObject(raw) }.getOrNull() ?: return null
        val info = json.optJSONObject("model_info")
        val arch = info?.optString("general.architecture").orEmpty()
        val context = info?.let { i ->
            i.positive("$arch.context_length") ?: i.keys().asSequence()
                .firstOrNull { it.endsWith(".context_length") }?.let { i.positive(it) }
        }
        val capabilities = json.optJSONArray("capabilities")?.let(::strings)
        val spec = ModelSpec(
            contextWindow = context,
            vision = capabilities?.let { Tri.of("vision" in it) } ?: Tri.UNKNOWN,
            reasoning = capabilities?.let { Tri.of("thinking" in it) } ?: Tri.UNKNOWN
        )
        return spec.takeUnless { it.isEmpty }
    }

    private fun items(array: JSONArray): List<JSONObject> =
        (0 until array.length()).mapNotNull { array.optJSONObject(it) }

    private fun strings(array: JSONArray): List<String> =
        (0 until array.length()).mapNotNull { array.optString(it).takeIf(String::isNotBlank) }

    /** A positive integer field, or null when it is missing, null, zero or not a number. */
    private fun JSONObject.positive(key: String): Int? {
        if (!has(key) || isNull(key)) return null
        val value = optLong(key, -1)
        return value.takeIf { it > 0 }?.coerceAtMost(Int.MAX_VALUE.toLong())?.toInt()
    }
}
