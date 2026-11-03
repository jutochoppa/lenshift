package dev.lenshift.app.ai

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Reasoning mapping tests.
 *
 * These assert the single most important invariant in the provider layer: Lenshift must
 * never send a reasoning parameter a model or protocol does not accept, because doing so
 * turns a working request into a 400.
 */
class ReasoningMapperTest {

    private fun caps(
        reasoning: Tri = Tri.YES,
        mechanism: ReasoningMechanism = ReasoningMechanism.EFFORT,
        strict: Boolean = false
    ) = ModelCapabilities(
        reasoning = reasoning,
        mechanism = mechanism,
        strictReasoningRejection = strict
    )

    @Test
    fun `auto mode sends nothing`() {
        val body = JSONObject()
        ReasoningMapper.applyOpenAiChat(body, ReasoningMode.AUTO, caps(), openRouterStyle = false)
        assertFalse("AUTO must not add any key", body.has("reasoning_effort"))
        assertFalse(body.has("reasoning"))
    }

    @Test
    fun `effort maps to the flat field for first party openai`() {
        val body = JSONObject()
        ReasoningMapper.applyOpenAiChat(body, ReasoningMode.HIGH, caps(), openRouterStyle = false)
        assertEquals("high", body.getString("reasoning_effort"))
        assertFalse("must not also send the nested form", body.has("reasoning"))
    }

    @Test
    fun `effort maps to the nested field for openrouter`() {
        val body = JSONObject()
        ReasoningMapper.applyOpenAiChat(body, ReasoningMode.LOW, caps(), openRouterStyle = true)
        assertTrue(body.has("reasoning"))
        assertEquals("low", body.getJSONObject("reasoning").getString("effort"))
        assertFalse("must not also send the flat form", body.has("reasoning_effort"))
    }

    @Test
    fun `maximum clamps to high because no api documents maximum`() {
        val body = JSONObject()
        ReasoningMapper.applyOpenAiChat(body, ReasoningMode.MAXIMUM, caps(), openRouterStyle = false)
        assertEquals("high", body.getString("reasoning_effort"))
    }

    @Test
    fun `off omits the parameter entirely`() {
        val body = JSONObject()
        ReasoningMapper.applyOpenAiChat(body, ReasoningMode.OFF, caps(), openRouterStyle = false)
        assertFalse("OFF must be expressed by omission", body.has("reasoning_effort"))
    }

    @Test
    fun `non reasoning model receives no reasoning parameter`() {
        val body = JSONObject()
        ReasoningMapper.applyOpenAiChat(
            body,
            ReasoningMode.HIGH,
            caps(reasoning = Tri.NO),
            openRouterStyle = false
        )
        assertFalse(body.has("reasoning_effort"))
    }

    @Test
    fun `models that reject effort are skipped`() {
        val body = JSONObject()
        ReasoningMapper.applyOpenAiChat(
            body,
            ReasoningMode.HIGH,
            caps(strict = true),
            openRouterStyle = false
        )
        assertFalse(body.has("reasoning_effort"))
    }

    @Test
    fun `anthropic thinking raises max_tokens above the budget`() {
        val body = JSONObject()
        val required = ReasoningMapper.applyAnthropic(
            body = body,
            mode = ReasoningMode.HIGH,
            capabilities = caps(mechanism = ReasoningMechanism.ANTHROPIC_BUDGET),
            explicitBudget = null,
            maxTokens = 1024
        )

        val thinking = body.getJSONObject("thinking")
        assertEquals("enabled", thinking.getString("type"))
        val budget = thinking.getInt("budget_tokens")

        assertTrue("budget must meet the documented minimum", budget >= 1024)
        assertTrue("max_tokens must exceed the budget", required != null && required > budget)
    }

    @Test
    fun `anthropic budget is clamped to the documented minimum`() {
        val body = JSONObject()
        ReasoningMapper.applyAnthropic(
            body = body,
            mode = ReasoningMode.LOW,
            capabilities = caps(mechanism = ReasoningMechanism.ANTHROPIC_BUDGET),
            explicitBudget = 10,
            maxTokens = 8192
        )
        val budget = body.getJSONObject("thinking").getInt("budget_tokens")
        assertTrue("Anthropic rejects budgets below 1024", budget >= 1024)
    }

    @Test
    fun `anthropic auto mode sends no thinking block`() {
        val body = JSONObject()
        ReasoningMapper.applyAnthropic(
            body, ReasoningMode.AUTO,
            caps(mechanism = ReasoningMechanism.ANTHROPIC_BUDGET), null, 4096
        )
        assertFalse(body.has("thinking"))
    }

    @Test
    fun `gemini off sets a zero thinking budget`() {
        val config = JSONObject()
        ReasoningMapper.applyGemini(
            config, ReasoningMode.OFF,
            caps(mechanism = ReasoningMechanism.GEMINI_THINKING), null
        )
        assertEquals(
            0,
            config.getJSONObject("thinkingConfig").getInt("thinkingBudget")
        )
    }

    @Test
    fun `gemini maximum uses the dynamic budget sentinel`() {
        val config = JSONObject()
        ReasoningMapper.applyGemini(
            config, ReasoningMode.MAXIMUM,
            caps(mechanism = ReasoningMechanism.GEMINI_THINKING), null
        )
        assertEquals(
            -1,
            config.getJSONObject("thinkingConfig").getInt("thinkingBudget")
        )
    }

    @Test
    fun `ollama maps to a level string`() {
        val body = JSONObject()
        ReasoningMapper.applyOllama(
            body, ReasoningMode.HIGH,
            caps(mechanism = ReasoningMechanism.BOOLEAN_OR_LEVEL)
        )
        assertEquals("high", body.getString("think"))
    }

    @Test
    fun `ollama off maps to boolean false`() {
        val body = JSONObject()
        ReasoningMapper.applyOllama(
            body, ReasoningMode.OFF,
            caps(mechanism = ReasoningMechanism.BOOLEAN_OR_LEVEL)
        )
        assertEquals(false, body.getBoolean("think"))
    }

    @Test
    fun `unsupported reason is reported for a non reasoning model`() {
        val reason = ReasoningMapper.unsupportedReason(caps(reasoning = Tri.NO))
        assertTrue(reason != null && reason.isNotBlank())
    }

    @Test
    fun `unsupported reason is null when reasoning is available`() {
        assertNull(ReasoningMapper.unsupportedReason(caps()))
    }

    @Test
    fun `is supported rejects a protocol with no mechanism`() {
        assertFalse(
            ReasoningMapper.isSupported(
                ReasoningMode.HIGH,
                caps(mechanism = ReasoningMechanism.NONE)
            )
        )
        assertTrue("AUTO is always allowed", ReasoningMapper.isSupported(
            ReasoningMode.AUTO,
            caps(mechanism = ReasoningMechanism.NONE)
        ))
    }

    // --------------------------------------------------------- per-model levels

    private fun levels(vararg modes: ReasoningMode, mechanism: ReasoningMechanism = ReasoningMechanism.EFFORT) =
        caps(mechanism = mechanism).copy(reasoningModes = modes.toList())

    @Test
    fun `a level the model lacks becomes its nearest documented level`() {
        val o3 = levels(ReasoningMode.LOW, ReasoningMode.MEDIUM, ReasoningMode.HIGH)
        assertEquals(ReasoningMode.LOW, ReasoningMapper.effectiveMode(ReasoningMode.MINIMAL, o3))
        assertEquals(ReasoningMode.HIGH, ReasoningMapper.effectiveMode(ReasoningMode.MAXIMUM, o3))
        assertEquals(ReasoningMode.MEDIUM, ReasoningMapper.effectiveMode(ReasoningMode.MEDIUM, o3))
        assertEquals("an unsupported off sends nothing", ReasoningMode.AUTO, ReasoningMapper.effectiveMode(ReasoningMode.OFF, o3))
        assertEquals("unknown levels pass through", ReasoningMode.MINIMAL, ReasoningMapper.effectiveMode(ReasoningMode.MINIMAL, caps()))

        val body = JSONObject()
        ReasoningMapper.applyOpenAiChat(body, ReasoningMode.MINIMAL, o3, openRouterStyle = false)
        assertEquals("low", body.getString("reasoning_effort"))
    }

    @Test
    fun `off is sent as none only to models that document it`() {
        val gpt51 = levels(ReasoningMode.OFF, ReasoningMode.LOW, ReasoningMode.MEDIUM, ReasoningMode.HIGH)
        val body = JSONObject()
        ReasoningMapper.applyOpenAiChat(body, ReasoningMode.OFF, gpt51, openRouterStyle = true)
        assertEquals("none", body.getJSONObject("reasoning").getString("effort"))

        val gemini25Pro = levels(ReasoningMode.LOW, ReasoningMode.HIGH, mechanism = ReasoningMechanism.GEMINI_THINKING)
        val config = JSONObject()
        ReasoningMapper.applyGemini(config, ReasoningMode.OFF, gemini25Pro, explicitBudget = null)
        assertFalse("a zero budget would be rejected by 2.5 Pro", config.has("thinkingConfig"))
    }

    @Test
    fun `anthropic budget and max_tokens stay under the model's output ceiling`() {
        val opus4 = caps(mechanism = ReasoningMechanism.ANTHROPIC_BUDGET).copy(maxOutputTokens = 32_000)
        val body = JSONObject().put("temperature", 0.2).put("top_k", 5).put("top_p", 0.5)
        val max = ReasoningMapper.applyAnthropic(body, ReasoningMode.MAXIMUM, opus4, explicitBudget = null, maxTokens = 4096)
        assertEquals(30_976, body.getJSONObject("thinking").getInt("budget_tokens"))
        assertEquals(32_000, max)
        assertFalse("thinking rejects temperature", body.has("temperature"))
        assertFalse(body.has("top_k"))
        assertFalse("top_p below 0.95 is rejected", body.has("top_p"))
    }

    @Test
    fun `offered levels follow the model then the protocol`() {
        assertEquals(listOf(ReasoningMode.LOW, ReasoningMode.HIGH), ReasoningMapper.levels(levels(ReasoningMode.LOW, ReasoningMode.HIGH)))
        assertEquals(listOf(ReasoningMode.LOW, ReasoningMode.MEDIUM, ReasoningMode.HIGH), ReasoningMapper.levels(caps()))
        assertTrue(ReasoningMapper.levels(caps(reasoning = Tri.NO)).isEmpty())
        assertTrue(ReasoningMapper.levels(caps(strict = true)).isEmpty())
        assertEquals(ReasoningMode.OFF, ReasoningMapper.levels(caps(mechanism = ReasoningMechanism.BOOLEAN_OR_LEVEL)).first())
    }

    @Test
    fun `each level names what it sends`() {
        assertEquals("Sends reasoning.effort: \"low\"", ReasoningMapper.wireValue(ReasoningMode.LOW, caps(), openRouterStyle = true))
        assertEquals("Sends reasoning_effort: \"high\"", ReasoningMapper.wireValue(ReasoningMode.MAXIMUM, caps(), openRouterStyle = false))
        assertEquals(
            "Sends thinking.budget_tokens: 8192",
            ReasoningMapper.wireValue(ReasoningMode.MEDIUM, caps(mechanism = ReasoningMechanism.ANTHROPIC_BUDGET), openRouterStyle = false)
        )
        assertEquals(
            "Sends thinkingBudget: 0",
            ReasoningMapper.wireValue(ReasoningMode.OFF, caps(mechanism = ReasoningMechanism.GEMINI_THINKING), openRouterStyle = false)
        )
        assertTrue(ReasoningMapper.wireValue(ReasoningMode.AUTO, caps(), openRouterStyle = false).startsWith("Nothing sent"))
    }
}
