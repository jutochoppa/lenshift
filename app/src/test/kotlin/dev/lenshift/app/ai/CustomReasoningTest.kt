package dev.lenshift.app.ai

import dev.lenshift.app.data.settings.AppSettings
import dev.lenshift.app.data.settings.toJson
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Reasoning levels the user deletes, adds ("min", "standard", "extended") and picks. */
class CustomReasoningTest {

    private val effort = ModelCapabilities(reasoning = Tri.YES, mechanism = ReasoningMechanism.EFFORT)
    private val relay = ProviderProfile(
        id = "relay", displayName = "Relay", protocol = ApiProtocol.OPENAI_RESPONSES,
        baseUrl = "https://relay.example/v1", modelId = "gpt-5.6"
    )

    private fun keys(profile: ProviderProfile) = ReasoningOptions.options(profile, effort).map { it.label }

    @Test
    fun `the built-in levels are filled in until the user changes them`() {
        assertEquals(listOf("Low", "Medium", "High"), keys(relay))
        assertEquals(ReasoningOptions.AUTOMATIC, ReasoningOptions.selectedKey(relay, effort))
    }

    @Test
    fun `levels of the user's own are added, picked and sent as written`() {
        val edited = listOf("min", "standard", "extended").fold(relay) { p, v -> ReasoningOptions.add(p, v, effort) }
        assertEquals(listOf("Low", "Medium", "High", "min", "standard", "extended"), keys(edited))
        assertEquals("extended", edited.customReasoning.selected)

        val picked = ReasoningOptions.select(edited, ReasoningOptions.options(edited, effort).first { it.label == "standard" }.key)
        val body = JSONObject()
        ReasoningMapper.applyOpenAiResponses(body, picked.reasoningMode, effort, picked.customReasoning.selected)
        assertEquals("standard", body.getJSONObject("reasoning").getString("effort"))
        assertEquals("auto", body.getJSONObject("reasoning").getString("summary"))

        val chat = JSONObject()
        ReasoningMapper.applyOpenAiChat(chat, picked.reasoningMode, effort, openRouterStyle = false, custom = "standard")
        assertEquals("standard", chat.getString("reasoning_effort"))
        assertEquals(
            "Sends reasoning.effort: \"standard\", as you wrote it",
            ReasoningMapper.wireValue(picked.reasoningMode, effort, true, null, "standard")
        )
    }

    @Test
    fun `built-in levels can be deleted and brought back`() {
        val high = ReasoningOptions.select(relay, "level:high")
        val deleted = ReasoningOptions.remove(high, "level:high", effort)
        assertEquals(listOf("Low", "Medium"), keys(deleted))
        assertEquals("deleting the level in use goes back to Automatic", ReasoningMode.AUTO, deleted.reasoningMode)
        val noLow = ReasoningOptions.remove(deleted, "level:low", effort)
        assertEquals(listOf("Medium"), keys(noLow))
        assertEquals(listOf("Low", "Medium", "High"), keys(ReasoningOptions.restoreDefaults(noLow)))
        // Typing a deleted level's name brings it back and picks it.
        val back = ReasoningOptions.add(noLow, "HIGH", effort)
        assertEquals(listOf("Medium", "High"), keys(back))
        assertEquals(ReasoningMode.HIGH, back.reasoningMode)
        assertTrue(back.customReasoning.added.isEmpty())
    }

    @Test
    fun `deleting the user's own level in use stops sending it`() {
        val own = ReasoningOptions.add(relay, "min", effort)
        val removed = ReasoningOptions.remove(own, "own:min", effort)
        assertNull(removed.customReasoning.selected)
        assertTrue(removed.customReasoning.added.isEmpty())
        val body = JSONObject()
        ReasoningMapper.applyOpenAiResponses(body, removed.reasoningMode, effort, removed.customReasoning.selected)
        assertFalse(body.has("reasoning"))
    }

    @Test
    fun `adding the same level twice keeps one`() {
        val twice = ReasoningOptions.add(ReasoningOptions.add(relay, "Standard", effort), " standard ", effort)
        assertEquals(listOf("Standard"), twice.customReasoning.added)
        assertEquals("Standard", twice.customReasoning.selected)
        assertEquals(relay, ReasoningOptions.add(relay, "   ", effort))
    }

    @Test
    fun `other protocols read the user's levels in their own terms`() {
        val anthropic = ModelCapabilities(reasoning = Tri.YES, mechanism = ReasoningMechanism.ANTHROPIC_BUDGET)
        val body = JSONObject()
        ReasoningMapper.applyAnthropic(body, ReasoningMode.AUTO, anthropic, null, 1000, custom = "6000")
        assertEquals(6000, body.getJSONObject("thinking").getInt("budget_tokens"))
        val word = JSONObject()
        assertNull(ReasoningMapper.applyAnthropic(word, ReasoningMode.AUTO, anthropic, null, 1000, custom = "extended"))
        assertFalse("a word Anthropic can't take sends nothing", word.has("thinking"))

        val gemini = ModelCapabilities(reasoning = Tri.YES, mechanism = ReasoningMechanism.GEMINI_THINKING)
        val level = JSONObject()
        ReasoningMapper.applyGemini(level, ReasoningMode.AUTO, gemini, null, custom = "low")
        assertEquals("low", level.getJSONObject("thinkingConfig").getString("thinkingLevel"))
        val budget = JSONObject()
        ReasoningMapper.applyGemini(budget, ReasoningMode.AUTO, gemini, null, custom = "2048")
        assertEquals(2048, budget.getJSONObject("thinkingConfig").getInt("thinkingBudget"))

        val ollama = ModelCapabilities(reasoning = Tri.YES, mechanism = ReasoningMechanism.BOOLEAN_OR_LEVEL)
        val think = JSONObject()
        ReasoningMapper.applyOllama(think, ReasoningMode.AUTO, ollama, custom = "off")
        assertEquals(false, think.get("think"))

        // A model that takes no reasoning gets none, whatever was typed.
        val none = JSONObject()
        ReasoningMapper.applyOpenAiResponses(none, ReasoningMode.AUTO, effort.copy(reasoning = Tri.NO), "extended")
        assertFalse(none.has("reasoning"))
    }

    @Test
    fun `each model keeps its own levels across a restart`() {
        val edited = ReasoningOptions.remove(ReasoningOptions.add(relay, "standard", effort), "level:low", effort)
            .withModel("gpt-6").selecting("gpt-6")
        val settings = AppSettings(profiles = listOf(edited))
        val loaded = AppSettings.fromJson(JSONObject(settings.toJson().toString())).profiles.single()
        assertEquals(CustomReasoning(), loaded.customReasoning)
        val back = loaded.selecting("gpt-5.6")
        assertEquals(CustomReasoning(selected = "standard", added = listOf("standard"), hidden = setOf("low")), back.customReasoning)
        assertEquals(edited.toJson().toString(), loaded.toJson().toString())
    }

    @Test
    fun `a picked level that isn't among the added ones is dropped on load`() {
        val json = relay.toJson().put(
            "customReasoning",
            JSONObject().put("selected", "ghost").put("added", org.json.JSONArray().put("min")).put("hidden", org.json.JSONArray())
        )
        val loaded = AppSettings.fromJson(JSONObject().put("profiles", org.json.JSONArray().put(json))).profiles.single()
        assertNull(loaded.customReasoning.selected)
        assertEquals(listOf("min"), loaded.customReasoning.added)
    }
}
