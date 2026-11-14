package dev.lenshift.app.ai

/**
 * A saved provider configuration.
 *
 * Generation, reasoning and capability fields below are the settings of the current
 * model ([modelId]); every other saved model keeps its own in [modelSettings], and
 * [selecting] swaps them, so adapters always read the fields of the model they call.
 *
 * The API key itself is never part of this object: it lives in [SecretStore] under
 * [secretAlias], so a serialised profile is safe to display, export for debugging, or
 * include in a bug report the user chooses to share.
 */
data class ProviderProfile(
    val id: String,
    val displayName: String,
    val protocol: ApiProtocol = ApiProtocol.OPENAI_CHAT,

    /** Base URL WITHOUT the protocol-specific path, e.g. `https://api.openai.com/v1`. */
    val baseUrl: String = "",

    /** The model requests use: the one picked in the chat or marked as current here. */
    val modelId: String = "",

    /** Other models saved for this provider, offered in the chat's model picker. */
    val models: List<String> = emptyList(),

    /** Alias of the API key inside the encrypted store. */
    val secretAlias: String = id,

    /** Extra headers the user needs (e.g. `HTTP-Referer` for OpenRouter). */
    val customHeaders: Map<String, String> = emptyMap(),

    // ---- generation parameters ------------------------------------------------
    val streaming: Boolean = true,
    val temperature: Float? = null,
    val topP: Float? = null,
    val topK: Int? = null,
    val maxOutputTokens: Int? = null,
    val contextWindowOverride: Int? = null,

    // ---- reasoning -----------------------------------------------------------
    val reasoningMode: ReasoningMode = ReasoningMode.AUTO,
    val reasoningTokenBudget: Int? = null,
    /** The user's own reasoning levels for the current model, and which one is picked. */
    val customReasoning: CustomReasoning = CustomReasoning(),

    // ---- images --------------------------------------------------------------
    val imageDetail: ImageDetail = ImageDetail.AUTO,
    val jpegQuality: Int = 85,
    val maxImageEdgePx: Int = 1568,

    // ---- transport -----------------------------------------------------------
    val timeoutMs: Long = 90_000L,
    val maxRetries: Int = 1,

    /** Raw JSON merged into the request body, for provider quirks we do not model. */
    val extraJsonParameters: String = "",

    // ---- capability overrides ------------------------------------------------
    val visionOverride: Tri = Tri.UNKNOWN,
    val reasoningOverride: Tri = Tri.UNKNOWN,

    /** Allow plain-HTTP base URLs (local gateways only; gated behind a warning). */
    val allowCleartext: Boolean = false,

    /** Explicit reasoning-mechanism override when auto-detection guesses wrong. */
    val mechanismOverride: ReasoningMechanism? = null,

    /** "This model creates images", when the provider's list does not say. */
    val imageOutputOverride: Tri = Tri.UNKNOWN,

    /** Settings of the saved models other than [modelId]. */
    val modelSettings: Map<String, ModelSettings> = emptyMap(),

    /** What the provider's model list reported for saved models. */
    val modelSpecs: Map<String, ModelSpec> = emptyMap()
) {
    val hasCredentials: Boolean get() = baseUrl.isNotBlank() && modelId.isNotBlank()

    /**
     * Every saved model in the order it was added, without blanks or duplicates. The
     * current model is included (first, if it was never in the saved list).
     */
    val allModels: List<String>
        get() {
            val saved = models.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
            val current = modelId.trim()
            return if (current.isEmpty() || current in saved) saved else listOf(current) + saved
        }

    /** The current model's settings, as stored in the fields above. */
    val currentSettings: ModelSettings
        get() = ModelSettings(
            temperature = temperature,
            topP = topP,
            topK = topK,
            maxOutputTokens = maxOutputTokens,
            contextWindowOverride = contextWindowOverride,
            reasoningMode = reasoningMode,
            reasoningTokenBudget = reasoningTokenBudget,
            customReasoning = customReasoning,
            visionOverride = visionOverride,
            reasoningOverride = reasoningOverride,
            mechanismOverride = mechanismOverride,
            imageOutputOverride = imageOutputOverride
        )

    /** Settings [model] uses when it is current (defaults if never changed). */
    fun settingsFor(model: String): ModelSettings =
        if (model == modelId) currentSettings else modelSettings[model] ?: ModelSettings()

    /**
     * What is known about [model]: the provider-reported spec, with gaps filled from
     * documented values ([KnownModels]). Null when neither knows the model.
     */
    fun specFor(model: String): ModelSpec? {
        val known = KnownModels.lookup(model)
        return modelSpecs[model]?.orElse(known) ?: known
    }

    /**
     * Adds [model] to the end of the saved list (no-op if present); the first becomes
     * current. [spec] is what the provider's list reported for it, if anything.
     */
    fun withModel(model: String, spec: ModelSpec? = null): ProviderProfile {
        val id = model.trim()
        if (id.isEmpty() || id in allModels) return this
        return copy(
            modelId = modelId.ifBlank { id },
            models = allModels + id,
            modelSpecs = if (spec == null) modelSpecs else modelSpecs + (id to spec)
        )
    }

    /** Records reported specs for the saved models found in a fetched list. */
    fun withSpecs(listed: List<ModelInfo>): ProviderProfile {
        val saved = allModels.toSet()
        val reported = listed.mapNotNull { info -> info.spec?.takeIf { info.id in saved }?.let { info.id to it } }
        return if (reported.isEmpty()) this else copy(modelSpecs = modelSpecs + reported)
    }

    /**
     * Removes [model] and its settings. Removing the current model makes the first
     * remaining one current, so a provider with any saved models always has a usable one.
     */
    fun withoutModel(model: String): ProviderProfile {
        val rest = allModels.filter { it != model }
        val trimmed = copy(models = rest, modelSettings = modelSettings - model, modelSpecs = modelSpecs - model)
        if (model != modelId) return trimmed
        val next = rest.firstOrNull().orEmpty()
        return trimmed.copy(modelId = next, modelSettings = trimmed.modelSettings - next)
            .applying(trimmed.modelSettings[next] ?: ModelSettings())
    }

    /**
     * Makes [model] current without reordering the saved list (saving it if new). The
     * outgoing model's settings are kept for it and the incoming model's are loaded.
     */
    fun selecting(model: String): ProviderProfile {
        val id = model.trim()
        if (id.isEmpty() || id == modelId) return this
        val saved = allModels
        val stashed = when {
            modelId.isBlank() -> modelSettings
            currentSettings == ModelSettings() -> modelSettings - modelId
            else -> modelSettings + (modelId to currentSettings)
        }
        return copy(modelId = id, models = if (id in saved) saved else saved + id, modelSettings = stashed - id)
            .applying(stashed[id] ?: ModelSettings())
    }

    /** These fields set to [settings]. */
    fun applying(settings: ModelSettings): ProviderProfile = copy(
        temperature = settings.temperature,
        topP = settings.topP,
        topK = settings.topK,
        maxOutputTokens = settings.maxOutputTokens,
        contextWindowOverride = settings.contextWindowOverride,
        reasoningMode = settings.reasoningMode,
        reasoningTokenBudget = settings.reasoningTokenBudget,
        customReasoning = settings.customReasoning,
        visionOverride = settings.visionOverride,
        reasoningOverride = settings.reasoningOverride,
        mechanismOverride = settings.mechanismOverride,
        imageOutputOverride = settings.imageOutputOverride
    )

    /** Whether [model] can make images here: its override, else its spec, and the protocol. */
    fun createsImages(model: String): Boolean {
        if (!protocol.canCreateImages) return false
        return when (settingsFor(model).imageOutputOverride) {
            Tri.YES -> true
            Tri.NO -> false
            Tri.UNKNOWN -> specFor(model)?.imageOutput == Tri.YES
        }
    }

    /**
     * Image-only models (DALL·E, gpt-image) cannot answer questions: picked in the chat,
     * they make a picture of what the user types.
     */
    fun isImageOnly(model: String): Boolean = createsImages(model) && specFor(model)?.textOutput == Tri.NO

    /** [model] saved here (added if new) and marked as creating images, unless already known to. */
    fun markingCreatesImages(model: String): ProviderProfile {
        val saved = withModel(model)
        if (saved.createsImages(model)) return saved
        return if (model == saved.modelId) {
            saved.copy(imageOutputOverride = Tri.YES)
        } else {
            saved.copy(modelSettings = saved.modelSettings + (model to saved.settingsFor(model).copy(imageOutputOverride = Tri.YES)))
        }
    }

    /** The URL actually used for a request, tolerating a trailing slash. */
    fun normalizedBase(): String = baseUrl.trim().trimEnd('/')

    companion object
}

/**
 * The settings that belong to one model rather than to its provider. Defaults mean "the
 * model's own default": nothing is sent for them.
 */
data class ModelSettings(
    val temperature: Float? = null,
    val topP: Float? = null,
    val topK: Int? = null,
    val maxOutputTokens: Int? = null,
    val contextWindowOverride: Int? = null,
    val reasoningMode: ReasoningMode = ReasoningMode.AUTO,
    val reasoningTokenBudget: Int? = null,
    val customReasoning: CustomReasoning = CustomReasoning(),
    val visionOverride: Tri = Tri.UNKNOWN,
    val reasoningOverride: Tri = Tri.UNKNOWN,
    val mechanismOverride: ReasoningMechanism? = null,
    val imageOutputOverride: Tri = Tri.UNKNOWN
)

/**
 * A model's reasoning levels as the user edited them: built-in levels they deleted, levels
 * of their own (any value the model takes, such as "min", "standard" or "extended"), and
 * the one of their own that is picked. A picked value is sent exactly as written and
 * replaces the built-in level.
 */
data class CustomReasoning(
    /** One of [added], sent as written; null uses the built-in level. */
    val selected: String? = null,
    /** The user's own levels, in the order added. */
    val added: List<String> = emptyList(),
    /** Ids of built-in levels the user deleted ("low", "minimal", …). */
    val hidden: Set<String> = emptySet()
)

enum class ImageDetail(val id: String, val label: String) {
    AUTO("auto", "Automatic"),
    LOW("low", "Low (fast, cheap)"),
    HIGH("high", "High (detailed)")
}
