package dev.lenshift.app.ai

/**
 * The wire protocol a provider profile speaks.
 *
 * These are NOT interchangeable. Each has a different request body, a different stream
 * envelope, different reasoning controls, and different multi-turn state rules, so each
 * gets its own adapter rather than being funnelled through one "generic JSON" client.
 */
enum class ApiProtocol(val id: String, val label: String, val description: String) {

    /** `POST {base}/chat/completions` — the widely-cloned Chat Completions shape. */
    OPENAI_CHAT(
        id = "openai_chat",
        label = "OpenAI-compatible Chat Completions",
        description = "POST /chat/completions with messages[]. Works with OpenAI, " +
            "OpenRouter, vLLM, LM Studio, LiteLLM and most self-hosted gateways."
    ),

    /** `POST {base}/responses` — the Responses API. Different body and stream shape. */
    OPENAI_RESPONSES(
        id = "openai_responses",
        label = "OpenAI Responses API",
        description = "POST /responses with input[]. Server-side state via previous_response_id; " +
            "reasoning items are preserved as opaque continuation state."
    ),

    /** `POST {base}/messages` — native Anthropic Messages API. */
    ANTHROPIC_MESSAGES(
        id = "anthropic_messages",
        label = "Anthropic Messages",
        description = "POST /messages with a top-level system field and content blocks. " +
            "Supports extended thinking with signed thinking blocks."
    ),

    /** `POST {base}/models/{model}:generateContent` — native Gemini API. */
    GEMINI(
        id = "gemini",
        label = "Google Gemini",
        description = "POST /models/{model}:generateContent with contents[]. " +
            "Thinking is controlled by thinkingConfig."
    ),

    /** `POST {base}/api/chat` — Ollama's local chat endpoint. */
    OLLAMA(
        id = "ollama",
        label = "Ollama (local)",
        description = "POST /api/chat against a local Ollama daemon. No API key required. " +
            "Use http://10.0.2.2:11434 from an emulator, or your LAN address."
    );

    /** Whether Lenshift can ask models on this protocol for images. */
    val canCreateImages: Boolean get() = this == OPENAI_CHAT || this == OPENAI_RESPONSES || this == GEMINI

    companion object {
        fun fromId(id: String?): ApiProtocol =
            entries.firstOrNull { it.id == id } ?: OPENAI_CHAT
    }
}

/** How much latitude the user gives the model before it answers. */
enum class ReasoningMode(val id: String, val label: String) {
    /** Send nothing; let the provider use its own default. */
    AUTO("auto", "Automatic"),

    /** Ask the provider to disable reasoning where the protocol allows it. */
    OFF("off", "Off"),

    MINIMAL("minimal", "Minimal"),
    LOW("low", "Low"),
    MEDIUM("medium", "Medium"),
    HIGH("high", "High"),

    /** The largest effort the provider documents. */
    MAXIMUM("maximum", "Maximum");

    companion object {
        fun fromId(id: String?): ReasoningMode =
            entries.firstOrNull { it.id == id } ?: AUTO
    }
}

/** Which concrete knob a protocol exposes for reasoning. */
enum class ReasoningMechanism {
    /** No documented control. The UI disables reasoning selection entirely. */
    NONE,

    /** OpenAI `reasoning.effort` (chat) or `reasoning.effort` (responses). */
    EFFORT,

    /** OpenRouter `reasoning: { effort }` / `{ max_tokens }` / `{ enabled }`. */
    OPENROUTER_EFFORT,

    /** Anthropic `thinking: { type: enabled, budget_tokens }`. */
    ANTHROPIC_BUDGET,

    /** Gemini `generationConfig.thinkingConfig`. */
    GEMINI_THINKING,

    /** Ollama-style `think: true|false|"level"`. */
    BOOLEAN_OR_LEVEL
}

/**
 * What a model can do. Populated from an explicit user override first, then from a
 * conservative built-in guess, and finally from live discovery where the provider
 * exposes it.
 *
 * Nothing here is invented: an unknown capability is [Tri.UNKNOWN], and the UI treats
 * unknown as "do not send the parameter".
 */
enum class Tri { YES, NO, UNKNOWN;
    companion object {
        fun of(value: Boolean?): Tri = when (value) {
            true -> YES
            false -> NO
            null -> UNKNOWN
        }
    }
}

data class ModelCapabilities(
    val vision: Tri = Tri.UNKNOWN,
    val reasoning: Tri = Tri.UNKNOWN,
    val mechanism: ReasoningMechanism = ReasoningMechanism.NONE,
    val streaming: Tri = Tri.YES,
    val toolUse: Tri = Tri.UNKNOWN,
    /** Null when genuinely unknown — never fabricated. */
    val contextWindow: Int? = null,
    /** The model's output ceiling (reported or documented), not the user's setting. */
    val maxOutputTokens: Int? = null,
    /** True when the provider rejects `reasoning_effort` on non-reasoning models. */
    val strictReasoningRejection: Boolean = false,
    /** Reasoning levels the model documents, weakest first; empty when unknown. */
    val reasoningModes: List<ReasoningMode> = emptyList(),
    /** Request parameters the model accepts ([ModelSpec.TEMPERATURE], ...); null when unknown. */
    val parameters: Set<String>? = null
) {
    /** True unless the model lists its parameters and [parameter] is not among them. */
    fun accepts(parameter: String): Boolean = parameters?.contains(parameter) ?: true
}
