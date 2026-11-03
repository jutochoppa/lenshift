package dev.lenshift.app.ai

/**
 * What one model accepts: reported by the provider's model list where it says so, or
 * taken from [KnownModels] for well-documented models. Every field is optional; null,
 * [Tri.UNKNOWN] or empty means "not reported", never "no".
 */
data class ModelSpec(
    val contextWindow: Int? = null,
    /** The model's output ceiling, not the user's setting. */
    val maxOutputTokens: Int? = null,
    val vision: Tri = Tri.UNKNOWN,
    val reasoning: Tri = Tri.UNKNOWN,
    /** Reasoning levels the model takes, weakest first; empty when not documented. */
    val reasoningModes: List<ReasoningMode> = emptyList(),
    /**
     * Request parameters the model accepts, by their Chat Completions names
     * ([TEMPERATURE], [TOP_P], ...). Null when the provider does not say.
     */
    val parameters: Set<String>? = null,
    /** Whether the model can answer with images, and with text. */
    val imageOutput: Tri = Tri.UNKNOWN,
    val textOutput: Tri = Tri.UNKNOWN,
    val source: Source = Source.PROVIDER
) {
    enum class Source { PROVIDER, BUILT_IN }

    val isEmpty: Boolean
        get() = contextWindow == null && maxOutputTokens == null && vision == Tri.UNKNOWN &&
            reasoning == Tri.UNKNOWN && reasoningModes.isEmpty() && parameters == null &&
            imageOutput == Tri.UNKNOWN && textOutput == Tri.UNKNOWN

    /** True unless the spec lists its parameters and [parameter] is not among them. */
    fun accepts(parameter: String): Boolean = parameters?.contains(parameter) ?: true

    /** This spec with its gaps filled from [other] (provider limits plus built-in levels). */
    fun orElse(other: ModelSpec?): ModelSpec = if (other == null) this else copy(
        contextWindow = contextWindow ?: other.contextWindow,
        maxOutputTokens = maxOutputTokens ?: other.maxOutputTokens,
        vision = if (vision != Tri.UNKNOWN) vision else other.vision,
        reasoning = if (reasoning != Tri.UNKNOWN) reasoning else other.reasoning,
        reasoningModes = reasoningModes.ifEmpty { other.reasoningModes },
        parameters = parameters ?: other.parameters,
        imageOutput = if (imageOutput != Tri.UNKNOWN) imageOutput else other.imageOutput,
        textOutput = if (textOutput != Tri.UNKNOWN) textOutput else other.textOutput
    )

    /** "128K context · 16K output · images · reasoning", or null when nothing is known. */
    fun summary(): String? = buildList {
        contextWindow?.let { add("${formatTokens(it)} context") }
        maxOutputTokens?.let { add("${formatTokens(it)} output") }
        when (vision) {
            Tri.YES -> add("image input")
            Tri.NO -> add("text only")
            Tri.UNKNOWN -> Unit
        }
        if (reasoning == Tri.YES) add("reasoning")
        if (imageOutput == Tri.YES) add(if (textOutput == Tri.NO) "image model" else "creates images")
    }.takeIf { it.isNotEmpty() }?.joinToString("  ·  ")

    companion object {
        const val TEMPERATURE = "temperature"
        const val TOP_P = "top_p"
        const val TOP_K = "top_k"
        const val MAX_TOKENS = "max_tokens"
        const val REASONING = "reasoning"

        /** 128000 -> "128K", 131072 -> "128K", 1048576 -> "1M", 2097152 -> "2M". */
        fun formatTokens(tokens: Int): String {
            // Vendors quote round thousands in decimal and powers of two in binary.
            val binary = tokens % 1000 != 0 && tokens % 1024 == 0
            val thousands = if (binary) tokens / 1024.0 else tokens / 1000.0
            return if (thousands >= 1000) {
                val millions = if (binary) thousands / 1024 else thousands / 1000
                "%.1f".format(java.util.Locale.ROOT, millions).removeSuffix(".0") + "M"
            } else {
                "${Math.round(thousands)}K"
            }
        }
    }
}

/** One entry of a provider's model list. */
data class ModelInfo(
    val id: String,
    /** The provider's display name, when it differs from the id. */
    val name: String? = null,
    val spec: ModelSpec? = null
)
