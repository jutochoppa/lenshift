package dev.lenshift.app.ai

import dev.lenshift.app.ai.ModelSpec.Companion.MAX_TOKENS
import dev.lenshift.app.ai.ModelSpec.Companion.REASONING
import dev.lenshift.app.ai.ModelSpec.Companion.TEMPERATURE
import dev.lenshift.app.ai.ModelSpec.Companion.TOP_K
import dev.lenshift.app.ai.ModelSpec.Companion.TOP_P
import dev.lenshift.app.ai.ReasoningMode.HIGH
import dev.lenshift.app.ai.ReasoningMode.LOW
import dev.lenshift.app.ai.ReasoningMode.MAXIMUM
import dev.lenshift.app.ai.ReasoningMode.MEDIUM
import dev.lenshift.app.ai.ReasoningMode.MINIMAL
import dev.lenshift.app.ai.ReasoningMode.OFF

/**
 * Published limits of widely used models, used when the provider's model list does not
 * report them (first-party OpenAI and Anthropic lists carry no limits at all).
 *
 * Only models whose limits are documented by their vendor are listed, and matches are
 * exact apart from vendor prefixes, dates and variant tags, so a new model never
 * inherits an older one's numbers. The UI labels these as typical values.
 */
object KnownModels {

    private val OPENAI_SAMPLING = setOf(TEMPERATURE, TOP_P, MAX_TOKENS)

    /** OpenAI reasoning models reject temperature and top_p. */
    private val OPENAI_REASONING = setOf(MAX_TOKENS, REASONING)
    private val ANTHROPIC = setOf(TEMPERATURE, TOP_P, TOP_K, MAX_TOKENS)
    private val ANTHROPIC_THINKING = ANTHROPIC + REASONING
    private val GEMINI = setOf(TEMPERATURE, TOP_P, TOP_K, MAX_TOKENS)
    private val GEMINI_THINKING = GEMINI + REASONING

    private val EFFORT = listOf(LOW, MEDIUM, HIGH)
    private val BUDGETS = listOf(MINIMAL, LOW, MEDIUM, HIGH, MAXIMUM)

    private class Entry(pattern: String, val spec: ModelSpec) {
        val regex = Regex(pattern)
    }

    private fun spec(
        context: Int,
        output: Int,
        vision: Boolean,
        reasoning: List<ReasoningMode>? = null,
        parameters: Set<String>
    ) = ModelSpec(
        contextWindow = context,
        maxOutputTokens = output,
        vision = Tri.of(vision),
        reasoning = Tri.of(reasoning != null),
        reasoningModes = reasoning.orEmpty(),
        parameters = parameters,
        source = ModelSpec.Source.BUILT_IN
    )

    // Patterns run against [normalize]d ids: dots become dashes ("gpt-4.1" -> "gpt-4-1").
    private val entries = listOf(
        // ---- OpenAI
        Entry("^gpt-4o-mini$", spec(128_000, 16_384, vision = true, parameters = OPENAI_SAMPLING)),
        Entry("^(chatgpt-)?gpt-4o$|^chatgpt-4o$", spec(128_000, 16_384, vision = true, parameters = OPENAI_SAMPLING)),
        Entry("^gpt-4-1(-mini|-nano)?$", spec(1_047_576, 32_768, vision = true, parameters = OPENAI_SAMPLING)),
        Entry("^gpt-4-turbo(-preview)?$", spec(128_000, 4_096, vision = true, parameters = OPENAI_SAMPLING)),
        Entry("^gpt-3-5-turbo$", spec(16_385, 4_096, vision = false, parameters = OPENAI_SAMPLING)),
        Entry("^gpt-5-chat$", spec(128_000, 16_384, vision = true, parameters = OPENAI_SAMPLING)),
        Entry("^gpt-5(-mini|-nano)?$", spec(400_000, 128_000, vision = true, listOf(MINIMAL, LOW, MEDIUM, HIGH), OPENAI_REASONING)),
        Entry("^gpt-5-1$", spec(400_000, 128_000, vision = true, listOf(OFF, LOW, MEDIUM, HIGH), OPENAI_REASONING)),
        // o1-mini and o1-preview reason but take no effort parameter.
        Entry("^o1-mini$", spec(128_000, 65_536, vision = false, emptyList(), setOf(MAX_TOKENS))),
        Entry("^o1-preview$", spec(128_000, 32_768, vision = false, emptyList(), setOf(MAX_TOKENS))),
        Entry("^o1$", spec(200_000, 100_000, vision = true, EFFORT, OPENAI_REASONING)),
        Entry("^o3-mini$", spec(200_000, 100_000, vision = false, EFFORT, OPENAI_REASONING)),
        Entry("^o3(-pro)?$", spec(200_000, 100_000, vision = true, EFFORT, OPENAI_REASONING)),
        Entry("^o4-mini$", spec(200_000, 100_000, vision = true, EFFORT, OPENAI_REASONING)),

        // ---- Anthropic (thinking is a token budget, so every level works)
        Entry("^claude-3-haiku$", spec(200_000, 4_096, vision = true, parameters = ANTHROPIC)),
        Entry("^claude-3-opus$", spec(200_000, 4_096, vision = true, parameters = ANTHROPIC)),
        Entry("^claude-3-5-(sonnet|haiku)$", spec(200_000, 8_192, vision = true, parameters = ANTHROPIC)),
        Entry("^claude-3-7-sonnet$", spec(200_000, 64_000, vision = true, BUDGETS, ANTHROPIC_THINKING)),
        Entry("^claude-(sonnet-4|sonnet-4-5|haiku-4-5|opus-4-5)$", spec(200_000, 64_000, vision = true, BUDGETS, ANTHROPIC_THINKING)),
        Entry("^claude-opus-4(-1)?$", spec(200_000, 32_000, vision = true, BUDGETS, ANTHROPIC_THINKING)),

        // ---- Google (previews keep their date tags, so these match by prefix)
        Entry("^gemini-2-5-flash-lite(-preview.*)?$", spec(1_048_576, 65_536, vision = true, listOf(OFF) + BUDGETS, GEMINI_THINKING)),
        Entry("^gemini-2-5-flash(-preview.*)?$", spec(1_048_576, 65_536, vision = true, listOf(OFF) + BUDGETS, GEMINI_THINKING)),
        // 2.5 Pro cannot turn thinking off.
        Entry("^gemini-2-5-pro(-preview.*|-exp.*)?$", spec(1_048_576, 65_536, vision = true, BUDGETS, GEMINI_THINKING)),
        Entry("^gemini-2-0-flash(-lite)?(-\\d{3})?$", spec(1_048_576, 8_192, vision = true, parameters = GEMINI)),
        Entry("^gemini-1-5-pro(-\\d{3})?$", spec(2_097_152, 8_192, vision = true, parameters = GEMINI)),
        Entry("^gemini-1-5-flash(-8b)?(-\\d{3})?$", spec(1_048_576, 8_192, vision = true, parameters = GEMINI)),

        // ---- image generation (limits vary by size, so only the outputs are listed)
        // Every gpt-image variant, including relays' own names ("gpt-image-2.6-sunburst").
        Entry("^(codex-)?gpt-image(-.*)?$", image(text = false, vision = true)),
        Entry("^dall-e-[23]$", image(text = false, vision = false)),
        Entry("^gemini-.*-image(-preview|-generation)?$", image(text = true, vision = true)),
        Entry("^gemini-2-0-flash-(preview|exp)-image-generation$", image(text = true, vision = true))
    )

    private fun image(text: Boolean, vision: Boolean) = ModelSpec(
        vision = Tri.of(vision),
        imageOutput = Tri.YES,
        textOutput = Tri.of(text),
        source = ModelSpec.Source.BUILT_IN
    )

    /** Built-in spec for [modelId], or null when the model is not in the table. */
    fun lookup(modelId: String): ModelSpec? {
        val id = normalize(modelId)
        if (id.isEmpty()) return null
        return entries.firstOrNull { it.regex.containsMatchIn(id) }?.spec
    }

    /**
     * "openai/gpt-4.1-2025-04-14" -> "gpt-4-1", "anthropic/claude-3.7-sonnet:thinking" ->
     * "claude-3-7-sonnet", "models/gemini-1.5-pro-002" -> "gemini-1-5-pro-002".
     */
    internal fun normalize(modelId: String): String {
        var id = modelId.trim().lowercase()
            .substringAfterLast('/')
            .substringBefore(':')
            .replace('.', '-')
            .replace('_', '-')
        while (true) {
            val trimmed = id.replace(SUFFIX, "")
            if (trimmed == id) break
            id = trimmed
        }
        return id
    }

    /** Dates ("-2024-08-06", "-20250514", "-0125"), "-latest", "-thinking" and "-v1-0". */
    private val SUFFIX = Regex("(-\\d{4}-\\d{2}-\\d{2}|-\\d{8}|-\\d{4}|-latest|-thinking|-v\\d+(-\\d+)?)$")
}
