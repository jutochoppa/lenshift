package dev.lenshift.app.ai

import org.json.JSONObject

/**
 * Translates Lenshift's abstract [ReasoningMode] into each protocol's real knob.
 *
 * The cardinal rule: if a mechanism is not documented for this protocol, or the model is
 * known not to accept it, we send **nothing**. Sending an unsupported parameter turns a
 * working request into a 400, which is worse than silently using the provider default.
 *
 * Every branch below documents which API surface it targets.
 */
object ReasoningMapper {

    /** Anthropic requires `budget_tokens >= 1024` and `< max_tokens`. */
    private const val ANTHROPIC_MIN_BUDGET = 1024

    /** Gemini 2.5 exposes discrete thinking budgets; 0 disables on Flash models. */
    private const val GEMINI_BUDGET_MINIMAL = 1024
    private const val GEMINI_BUDGET_LOW = 4096
    private const val GEMINI_BUDGET_MEDIUM = 12288
    private const val GEMINI_BUDGET_HIGH = 24576

    /**
     * Applies reasoning controls to an [OI] Chat Completions body.
     *
     * Two distinct shapes exist in the wild:
     *  - `reasoning_effort: "low"|"medium"|"high"` (first-party and most clones)
     *  - `reasoning: { effort }` (OpenRouter and several gateways)
     */
    fun applyOpenAiChat(
        body: JSONObject,
        mode: ReasoningMode,
        capabilities: ModelCapabilities,
        openRouterStyle: Boolean
    ) {
        val level = effectiveMode(mode, capabilities)
        if (level == ReasoningMode.AUTO) return
        if (capabilities.strictReasoningRejection) return
        if (capabilities.reasoning == Tri.NO) return

        val effort = effortString(level, capabilities) ?: return

        if (openRouterStyle) {
            body.put(
                "reasoning",
                JSONObject().put("effort", effort)
            )
        } else {
            body.put("reasoning_effort", effort)
        }
    }

    /**
     * Applies reasoning to an [OI] Responses API body.
     *
     * The Responses API takes a nested `reasoning: { effort }` object and, for
     * reasoning models, expects `include: ["reasoning.encrypted_content"]` when the
     * caller wants to continue a conversation statelessly.
     */
    fun applyOpenAiResponses(
        body: JSONObject,
        mode: ReasoningMode,
        capabilities: ModelCapabilities
    ) {
        val level = effectiveMode(mode, capabilities)
        if (level == ReasoningMode.AUTO) return
        if (capabilities.reasoning == Tri.NO) return

        val effort = effortString(level, capabilities) ?: return
        body.put("reasoning", JSONObject().put("effort", effort))
    }

    /**
     * Applies Anthropic extended thinking.
     *
     * `thinking: { type: "enabled", budget_tokens: N }`. Anthropic rejects
     * `budget_tokens` below 1024 and requires `budget_tokens < max_tokens`, so the
     * caller's max_tokens must be raised accordingly.
     *
     * @return the minimum `max_tokens` the caller must use, or null if unchanged.
     */
    fun applyAnthropic(
        body: JSONObject,
        mode: ReasoningMode,
        capabilities: ModelCapabilities,
        explicitBudget: Int?,
        maxTokens: Int?
    ): Int? {
        val level = effectiveMode(mode, capabilities)
        if (level == ReasoningMode.AUTO) return null
        if (capabilities.reasoning == Tri.NO) return null

        if (level == ReasoningMode.OFF) {
            // Anthropic has no explicit "off"; omitting the field is the documented way
            // to leave thinking disabled for models where it is opt-in.
            return null
        }

        // The budget and the answer must both fit under the model's output ceiling.
        val budget = anthropicBudget(level, capabilities, explicitBudget)
        val ceiling = anthropicCeiling(capabilities)
        val requiredMax = budget + 1024
        val raised = if (maxTokens == null || maxTokens < requiredMax) requiredMax else maxTokens
        val effectiveMax = if (ceiling != null) raised.coerceAtMost(ceiling) else raised

        // Thinking rejects temperature and top_k changes, and top_p below 0.95.
        body.remove("temperature")
        body.remove("top_k")
        if (body.has("top_p") && body.optDouble("top_p", 1.0) < 0.95) body.remove("top_p")

        body.put(
            "thinking",
            JSONObject()
                .put("type", "enabled")
                .put("budget_tokens", budget)
        )
        return effectiveMax
    }

    /**
     * Applies Gemini `generationConfig.thinkingConfig`.
     *
     * Gemini 2.5+/3 use `thinkingBudget` (tokens) or `thinkingLevel` depending on the
     * model generation. We send `thinkingBudget` because it is accepted across the
     * family, and `includeThoughts` so a summary can be surfaced when the model
     * provides one.
     */
    fun applyGemini(
        generationConfig: JSONObject,
        mode: ReasoningMode,
        capabilities: ModelCapabilities,
        explicitBudget: Int?
    ) {
        val level = effectiveMode(mode, capabilities)
        if (level == ReasoningMode.AUTO) return
        if (capabilities.reasoning == Tri.NO) return

        val config = JSONObject()

        when (level) {
            ReasoningMode.OFF -> config.put("thinkingBudget", 0)
            ReasoningMode.MINIMAL -> config.put("thinkingBudget", explicitBudget ?: GEMINI_BUDGET_MINIMAL)
            ReasoningMode.LOW -> config.put("thinkingBudget", explicitBudget ?: GEMINI_BUDGET_LOW)
            ReasoningMode.MEDIUM -> config.put("thinkingBudget", explicitBudget ?: GEMINI_BUDGET_MEDIUM)
            ReasoningMode.HIGH -> config.put("thinkingBudget", explicitBudget ?: GEMINI_BUDGET_HIGH)
            ReasoningMode.MAXIMUM -> {
                // -1 means "dynamic / model decides" in the Gemini API.
                config.put("thinkingBudget", explicitBudget ?: -1)
            }
            ReasoningMode.AUTO -> return
        }

        config.put("includeThoughts", true)
        generationConfig.put("thinkingConfig", config)
    }

    /**
     * Applies Ollama's `think` field. Ollama accepts a boolean on models that support
     * it, and a level string on newer builds.
     */
    fun applyOllama(body: JSONObject, mode: ReasoningMode, capabilities: ModelCapabilities) {
        val level = effectiveMode(mode, capabilities)
        if (level == ReasoningMode.AUTO) return
        if (capabilities.reasoning == Tri.NO) return

        when (level) {
            ReasoningMode.OFF -> body.put("think", false)
            ReasoningMode.MINIMAL, ReasoningMode.LOW -> body.put("think", "low")
            ReasoningMode.MEDIUM -> body.put("think", true)
            ReasoningMode.HIGH, ReasoningMode.MAXIMUM -> body.put("think", "high")
            ReasoningMode.AUTO -> Unit
        }
    }

    /**
     * True when the mode cannot be honoured by this protocol+model and the UI should
     * grey it out rather than pretend.
     */
    fun isSupported(mode: ReasoningMode, capabilities: ModelCapabilities): Boolean {
        if (mode == ReasoningMode.AUTO) return true
        if (capabilities.reasoning == Tri.NO) return false
        if (capabilities.mechanism == ReasoningMechanism.NONE) return false
        if (capabilities.strictReasoningRejection) return false
        return true
    }

    /** Human-readable explanation for a disabled control. */
    fun unsupportedReason(capabilities: ModelCapabilities): String? = when {
        capabilities.reasoning == Tri.NO ->
            "This model does not accept reasoning controls."
        capabilities.mechanism == ReasoningMechanism.NONE ->
            "This protocol has no documented reasoning parameter."
        capabilities.strictReasoningRejection ->
            "This model rejects effort parameters."
        else -> null
    }

    private fun effortString(mode: ReasoningMode, capabilities: ModelCapabilities): String? = when (mode) {
        ReasoningMode.MINIMAL -> "minimal"
        ReasoningMode.LOW -> "low"
        ReasoningMode.MEDIUM -> "medium"
        ReasoningMode.HIGH -> "high"
        // "maximum" is not a documented effort value anywhere; "high" is the ceiling.
        ReasoningMode.MAXIMUM -> "high"
        // Models that document turning reasoning off take "none"; elsewhere "off" is
        // expressed by omission.
        ReasoningMode.OFF -> if (ReasoningMode.OFF in capabilities.reasoningModes) "none" else null
        ReasoningMode.AUTO -> null
    }

    /** The model's output ceiling when it leaves room for a minimum budget and an answer. */
    private fun anthropicCeiling(capabilities: ModelCapabilities): Int? =
        capabilities.maxOutputTokens?.takeIf { it > ANTHROPIC_MIN_BUDGET + 1024 }

    private fun anthropicBudget(level: ReasoningMode, capabilities: ModelCapabilities, explicitBudget: Int?): Int {
        val budget = explicitBudget?.coerceAtLeast(ANTHROPIC_MIN_BUDGET) ?: anthropicBudgetFor(level)
        val ceiling = anthropicCeiling(capabilities) ?: return budget
        return budget.coerceAtMost(ceiling - 1024)
    }

    /**
     * The level actually sent. When the model documents its levels and [mode] is not one
     * of them (a level chosen for another model), the nearest documented level is used,
     * the stronger one on a tie; an unsupported "off" sends nothing.
     */
    fun effectiveMode(mode: ReasoningMode, capabilities: ModelCapabilities): ReasoningMode {
        val levels = capabilities.reasoningModes
        if (mode == ReasoningMode.AUTO || levels.isEmpty() || mode in levels) return mode
        if (mode == ReasoningMode.OFF) return ReasoningMode.AUTO
        return levels.filter { it != ReasoningMode.OFF }
            .minWithOrNull(
                compareBy<ReasoningMode> { kotlin.math.abs(it.ordinal - mode.ordinal) }
                    .thenByDescending { it.ordinal }
            )
            ?: ReasoningMode.AUTO
    }

    /**
     * Levels to offer for this model, weakest first, without Automatic: the model's
     * documented levels, else the ones its protocol can express. Empty when the model
     * takes no reasoning setting.
     */
    fun levels(capabilities: ModelCapabilities): List<ReasoningMode> {
        if (unsupportedReason(capabilities) != null) return emptyList()
        if (capabilities.reasoningModes.isNotEmpty()) return capabilities.reasoningModes
        return when (capabilities.mechanism) {
            ReasoningMechanism.NONE -> emptyList()
            ReasoningMechanism.EFFORT,
            ReasoningMechanism.OPENROUTER_EFFORT ->
                listOf(ReasoningMode.LOW, ReasoningMode.MEDIUM, ReasoningMode.HIGH)
            ReasoningMechanism.ANTHROPIC_BUDGET -> listOf(
                ReasoningMode.MINIMAL, ReasoningMode.LOW, ReasoningMode.MEDIUM,
                ReasoningMode.HIGH, ReasoningMode.MAXIMUM
            )
            ReasoningMechanism.GEMINI_THINKING -> ReasoningMode.entries.filter { it != ReasoningMode.AUTO }
            ReasoningMechanism.BOOLEAN_OR_LEVEL -> listOf(
                ReasoningMode.OFF, ReasoningMode.LOW, ReasoningMode.MEDIUM, ReasoningMode.HIGH
            )
        }
    }

    /**
     * What a level puts in the request, in the provider's own terms, e.g.
     * `reasoning_effort: "low"`, `thinking.budget_tokens: 8192` or `think: "high"`.
     */
    fun wireValue(
        mode: ReasoningMode,
        capabilities: ModelCapabilities,
        openRouterStyle: Boolean,
        explicitBudget: Int? = null
    ): String {
        val level = effectiveMode(mode, capabilities)
        if (level == ReasoningMode.AUTO) return "Nothing sent; the model uses its default."
        return when (capabilities.mechanism) {
            ReasoningMechanism.NONE -> "Nothing sent."
            ReasoningMechanism.EFFORT, ReasoningMechanism.OPENROUTER_EFFORT -> {
                val effort = effortString(level, capabilities)
                when {
                    effort == null -> "Nothing sent; the model uses its default."
                    openRouterStyle || capabilities.mechanism == ReasoningMechanism.OPENROUTER_EFFORT ->
                        "Sends reasoning.effort: \"$effort\""
                    else -> "Sends reasoning_effort: \"$effort\""
                }
            }
            ReasoningMechanism.ANTHROPIC_BUDGET -> if (level == ReasoningMode.OFF) {
                "Nothing sent; thinking stays off."
            } else {
                "Sends thinking.budget_tokens: ${anthropicBudget(level, capabilities, explicitBudget)}"
            }
            ReasoningMechanism.GEMINI_THINKING -> "Sends thinkingBudget: " + when (level) {
                ReasoningMode.OFF -> "0"
                ReasoningMode.MINIMAL -> "${explicitBudget ?: GEMINI_BUDGET_MINIMAL}"
                ReasoningMode.LOW -> "${explicitBudget ?: GEMINI_BUDGET_LOW}"
                ReasoningMode.MEDIUM -> "${explicitBudget ?: GEMINI_BUDGET_MEDIUM}"
                ReasoningMode.HIGH -> "${explicitBudget ?: GEMINI_BUDGET_HIGH}"
                else -> "${explicitBudget ?: -1} (dynamic)"
            }
            ReasoningMechanism.BOOLEAN_OR_LEVEL -> "Sends think: " + when (level) {
                ReasoningMode.OFF -> "false"
                ReasoningMode.MINIMAL, ReasoningMode.LOW -> "\"low\""
                ReasoningMode.MEDIUM -> "true"
                else -> "\"high\""
            }
        }
    }

    private fun anthropicBudgetFor(mode: ReasoningMode): Int = when (mode) {
        ReasoningMode.MINIMAL -> 1024
        ReasoningMode.LOW -> 2048
        ReasoningMode.MEDIUM -> 8192
        ReasoningMode.HIGH -> 16384
        ReasoningMode.MAXIMUM -> 32768
        else -> 8192
    }
}
