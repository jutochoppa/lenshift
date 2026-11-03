package dev.lenshift.app.ai

/**
 * Conservative, *documented* capability inference.
 *
 * This exists so the UI can pre-tick sensible boxes, never so the app can invent a
 * context window. Rules:
 *
 *  - Vision and reasoning are inferred only from well-known model families.
 *  - Everything else is [Tri.UNKNOWN], which callers must treat as "do not send".
 *  - No context-window number is ever produced here. A wrong number silently truncates
 *    requests, so the field stays null unless the user or a `/models` response supplies
 *    a real value.
 */
object CapabilityInference {

    private val visionHints = listOf(
        "gpt-4o", "gpt-4.1", "gpt-4-turbo", "gpt-4-vision", "gpt-5",
        "o3", "o4", "claude-3", "claude-4", "claude-sonnet", "claude-opus",
        "gemini-1.5", "gemini-2", "gemini-3", "gemini-pro-vision",
        "llava", "qwen-vl", "qwen2-vl", "qwen2.5-vl", "internvl",
        "llama-3.2-vision", "llama-4", "pixtral", "molmo", "minicpm-v",
        "grok-vision", "grok-2-vision", "grok-4"
    )

    private val reasoningHints = listOf(
        "o1", "o3", "o4-mini", "gpt-5",
        "claude-opus-4", "claude-sonnet-4", "claude-3-7-sonnet", "claude-4",
        "gemini-2.5", "gemini-3",
        "deepseek-reasoner", "deepseek-r1", "qwq", "qwen3",
        "grok-4", "magistral", "kimi-k2-thinking"
    )

    /** Models documented as *rejecting* an effort parameter outright. */
    private val noEffortSupport = listOf(
        "o1-preview", "o1-mini"
    )

    fun infer(protocol: ApiProtocol, modelId: String): ModelCapabilities {
        val id = modelId.lowercase()
        val vision = when {
            visionHints.any { id.contains(it) } -> Tri.YES
            else -> Tri.UNKNOWN
        }
        val reasoning = when {
            reasoningHints.any { id.contains(it) } -> Tri.YES
            else -> Tri.UNKNOWN
        }

        return ModelCapabilities(
            vision = vision,
            reasoning = reasoning,
            mechanism = if (reasoning == Tri.YES) mechanismFor(protocol) else ReasoningMechanism.NONE,
            streaming = Tri.YES,
            contextWindow = null,
            maxOutputTokens = null,
            strictReasoningRejection = noEffortSupport.any { id.contains(it) }
        )
    }

    fun mechanismFor(protocol: ApiProtocol): ReasoningMechanism = when (protocol) {
        ApiProtocol.OPENAI_CHAT,
        ApiProtocol.OPENAI_RESPONSES -> ReasoningMechanism.EFFORT
        ApiProtocol.ANTHROPIC_MESSAGES -> ReasoningMechanism.ANTHROPIC_BUDGET
        ApiProtocol.GEMINI -> ReasoningMechanism.GEMINI_THINKING
        ApiProtocol.OLLAMA -> ReasoningMechanism.BOOLEAN_OR_LEVEL
    }

    /**
     * Effective capabilities of the profile's current model.
     *
     * Each field takes the first answer from: the user's override for this model, what
     * the provider's model list reported, Lenshift's table of documented models
     * ([KnownModels]), and finally the name-based guess above.
     */
    fun forProfile(profile: ProviderProfile): ModelCapabilities {
        val inferred = infer(profile.protocol, profile.modelId)
        val spec = profile.specFor(profile.modelId)

        fun pick(override: Tri, reported: Tri?, guessed: Tri): Tri = when {
            override != Tri.UNKNOWN -> override
            reported != null && reported != Tri.UNKNOWN -> reported
            else -> guessed
        }
        val vision = pick(profile.visionOverride, spec?.vision, inferred.vision)
        val reasoning = pick(profile.reasoningOverride, spec?.reasoning, inferred.reasoning)
        val mechanism = profile.mechanismOverride
            ?: if (reasoning == Tri.YES) mechanismFor(profile.protocol) else ReasoningMechanism.NONE

        return inferred.copy(
            vision = vision,
            reasoning = reasoning,
            mechanism = mechanism,
            contextWindow = profile.contextWindowOverride ?: spec?.contextWindow,
            maxOutputTokens = spec?.maxOutputTokens,
            // A spec that lists its parameters without "reasoning" takes no reasoning setting.
            strictReasoningRejection = inferred.strictReasoningRejection ||
                (profile.reasoningOverride != Tri.YES && spec?.accepts(ModelSpec.REASONING) == false),
            reasoningModes = spec?.reasoningModes.orEmpty(),
            parameters = spec?.parameters
        )
    }
}
