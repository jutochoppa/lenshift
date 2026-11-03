package dev.lenshift.app.data.settings

import dev.lenshift.app.ai.ModelSettings
import dev.lenshift.app.ai.ModelSpec
import dev.lenshift.app.ai.ProviderProfile
import dev.lenshift.app.ai.ReasoningMode
import dev.lenshift.app.ai.Tri
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderModelsTest {

    private val base = ProviderProfile(id = "p", displayName = "OpenRouter", baseUrl = "https://x/v1")

    @Test
    fun `adding models keeps the first as current and ignores duplicates and blanks`() {
        val p = base.withModel("a").withModel(" b ").withModel("a").withModel("  ")
        assertEquals("a", p.modelId)
        assertEquals(listOf("a", "b"), p.allModels)
    }

    @Test
    fun `selecting a model makes it current without reordering the list`() {
        val p = base.withModel("a").withModel("b").withModel("c").selecting("c")
        assertEquals("c", p.modelId)
        assertEquals(listOf("a", "b", "c"), p.allModels)
        assertEquals(listOf("a", "b", "c"), p.selecting("a").selecting("b").allModels)
        // A typed id that was not saved yet is saved by selecting it.
        assertEquals(listOf("a", "b", "c", "d"), p.selecting("d").allModels)
    }

    @Test
    fun `removing the current model promotes the first remaining one`() {
        val p = base.withModel("a").withModel("b").withModel("c").selecting("b")
        assertEquals(listOf("a", "c"), p.withoutModel("b").allModels)
        assertEquals("a", p.withoutModel("b").modelId)
        assertEquals("b", p.withoutModel("c").modelId)
        assertEquals("", base.withModel("a").withoutModel("a").modelId)
    }

    @Test
    fun `profiles saved before this change keep their current model first`() {
        val legacy = base.copy(modelId = "x", models = listOf("y", "z"))
        assertEquals(listOf("x", "y", "z"), legacy.allModels)
        assertEquals(listOf("x", "y", "z"), legacy.selecting("z").allModels)
    }

    @Test
    fun `each model keeps its own settings when switching`() {
        val a = base.withModel("a").withModel("b")
            .copy(temperature = 0.2f, reasoningMode = ReasoningMode.HIGH, maxOutputTokens = 900)
        val b = a.selecting("b")
        assertEquals("a new model starts at its defaults", ModelSettings(), b.currentSettings)
        assertEquals(0.2f, b.settingsFor("a").temperature)

        val bTuned = b.copy(temperature = 1.1f, visionOverride = Tri.NO)
        val backToA = bTuned.selecting("a")
        assertEquals(0.2f, backToA.temperature)
        assertEquals(ReasoningMode.HIGH, backToA.reasoningMode)
        assertEquals(900, backToA.maxOutputTokens)
        assertEquals(Tri.UNKNOWN, backToA.visionOverride)
        assertEquals(1.1f, backToA.settingsFor("b").temperature)
        assertEquals("the current model is never duplicated in the map", setOf("b"), backToA.modelSettings.keys)
    }

    @Test
    fun `removing the current model loads the promoted model's settings and drops its own`() {
        val p = base.withModel("a").withModel("b").copy(temperature = 0.3f)
            .selecting("b").copy(temperature = 0.9f, topP = 0.5f)
            .selecting("a")
        val removed = p.withoutModel("a")
        assertEquals("b", removed.modelId)
        assertEquals(0.9f, removed.temperature)
        assertEquals(0.5f, removed.topP)
        assertTrue(removed.modelSettings.isEmpty())

        val withSpec = base.withModel("a", ModelSpec(contextWindow = 10)).withModel("b")
        assertEquals(emptySet<String>(), withSpec.withoutModel("a").modelSpecs.keys)
    }

    @Test
    fun `per model settings and reported specs survive a round trip`() {
        val p = base.withModel("a", ModelSpec(contextWindow = 128_000, vision = Tri.YES, parameters = setOf("temperature")))
            .withModel("b", ModelSpec(reasoning = Tri.YES, reasoningModes = listOf(ReasoningMode.LOW, ReasoningMode.HIGH)))
            .copy(temperature = 0.4f)
            .selecting("b")
            .copy(reasoningMode = ReasoningMode.LOW, reasoningTokenBudget = 2048)
        val restored = AppSettings.fromJson(AppSettings(profiles = listOf(p)).toJson()).profiles.single()
        assertEquals(p.modelSettings, restored.modelSettings)
        assertEquals(p.currentSettings, restored.currentSettings)
        assertEquals(p.modelSpecs, restored.modelSpecs)
        assertEquals(0.4f, restored.selecting("a").temperature)
        assertNull(restored.modelSpecs.getValue("b").parameters)
    }

    @Test
    fun `saved models survive a settings round trip and old settings load without them`() {
        val p = base.withModel("a").withModel("b")
        val restored = AppSettings.fromJson(AppSettings(profiles = listOf(p)).toJson()).profiles.single()
        assertEquals(listOf("a", "b"), restored.allModels)

        val legacy = JSONObject(AppSettings(profiles = listOf(base.copy(modelId = "only"))).toJson().toString())
        legacy.getJSONArray("profiles").getJSONObject(0).remove("models")
        assertEquals(listOf("only"), AppSettings.fromJson(legacy).profiles.single().allModels)
    }
}
