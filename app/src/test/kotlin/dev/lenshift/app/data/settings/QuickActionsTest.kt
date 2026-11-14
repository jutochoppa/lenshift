package dev.lenshift.app.data.settings

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class QuickActionsTest {

    private val check = QuickAction.custom("", "is this correct?", id = "custom-1")

    private fun reload(settings: AppSettings) = AppSettings.fromJson(JSONObject(settings.toJson().toString()))

    @Test
    fun `a new install offers the stock actions and lets the model decide`() {
        val settings = AppSettings()
        assertEquals(QuickAction.STOCK, settings.quickActions)
        assertEquals(QuickAction.ASK, settings.defaultQuickAction)
    }

    @Test
    fun `custom actions, edits, deletions and the default survive a restart`() {
        val settings = AppSettings()
            .withAction(check)
            .withoutAction(QuickAction.TRANSLATE.id)
            .withAction(QuickAction.SOLVE.edited("Solve it", "Solve it step by step."))
            .copy(defaultActionId = check.id)
        val loaded = reload(settings)
        assertEquals(settings.quickActions, loaded.quickActions)
        assertEquals(check, loaded.defaultQuickAction)
        assertEquals("is this correct?", loaded.quickActions.last().label)
        assertFalse(loaded.quickActions.any { it.id == QuickAction.TRANSLATE.id })
    }

    @Test
    fun `settings saved before custom actions keep the stock list and their default`() {
        val old = JSONObject().put("profiles", JSONArray()).put("defaultAction", "solve")
        val loaded = AppSettings.fromJson(old)
        assertEquals(QuickAction.STOCK, loaded.quickActions)
        assertEquals(QuickAction.SOLVE, loaded.defaultQuickAction)
    }

    @Test
    fun `deleting every action leaves none after a restart`() {
        val empty = QuickAction.STOCK.fold(AppSettings()) { s, a -> s.withoutAction(a.id) }
        assertEquals(emptyList<QuickAction>(), reload(empty).quickActions)
    }

    @Test
    fun `deleting the default action lets the model decide again`() {
        val settings = AppSettings().withAction(check).copy(defaultActionId = check.id).withoutAction(check.id)
        assertEquals(QuickAction.ASK.id, settings.defaultActionId)
        assertEquals(QuickAction.ASK, settings.defaultQuickAction)
        // A default that names an action no longer in the list falls back the same way.
        assertEquals(QuickAction.ASK, AppSettings().withoutAction("solve").copy(defaultActionId = "solve").defaultQuickAction)
    }

    @Test
    fun `an edited action keeps its place and id`() {
        val settings = AppSettings().withAction(QuickAction.EXPLAIN.edited("Explain", "Explain like I'm five."))
        assertEquals(QuickAction.STOCK.map { it.id }, settings.quickActions.map { it.id })
        assertEquals("Explain like I'm five.", settings.quickActions.first().prompt)
        assertEquals(QuickAction.EXPLAIN, settings.quickActions.first().original)
        assertNull(QuickAction.SOLVE.original)
    }

    @Test
    fun `deleted stock actions come back after the user's own, once`() {
        val settings = AppSettings().withoutAction("explain").withoutAction("define").withAction(check)
        assertEquals(listOf(QuickAction.EXPLAIN, QuickAction.DEFINE), settings.deletedStockActions)
        val restored = settings.withStockRestored()
        assertEquals(check, restored.quickActions[restored.quickActions.size - 3])
        assertEquals(listOf("explain", "define"), restored.quickActions.takeLast(2).map { it.id })
        assertEquals(restored, restored.withStockRestored())
        assertTrue(restored.deletedStockActions.isEmpty())
    }

    @Test
    fun `moving an action changes the chip order and stops at the ends`() {
        val settings = AppSettings().withAction(check)
        val first = settings.movingAction(check.id, -settings.quickActions.size)
        assertEquals(check, first.quickActions.first())
        assertEquals(first, first.movingAction(check.id, -1))
        assertEquals(listOf("translate", "explain"), settings.movingAction("explain", 1).quickActions.take(2).map { it.id })
        assertEquals(settings, settings.movingAction("missing", 1))
    }

    @Test
    fun `a blank name takes the prompt's first sentence, shortened for the chip`() {
        assertEquals("is this correct?", QuickAction.labelFor("  ", "is this correct?\nCheck every step."))
        assertEquals("Is this correct?", QuickAction.labelFor("", "Is this correct? Check each step and point out any mistake."))
        assertEquals("Use 3.5 m/s here", QuickAction.labelFor("", "Use 3.5 m/s here"))
        val long = QuickAction.labelFor("", "Check this proof line by line and tell me where it goes wrong")
        // Cut to fit, without a space left before the ellipsis.
        assertEquals("Check this proof line by line and tell…", long)
        assertTrue(long.length <= QuickAction.MAX_LABEL)
        assertEquals("Check it", QuickAction.labelFor("  Check   it ", "anything"))
    }

    @Test
    fun `saved actions without an id or prompt, or repeated, are skipped`() {
        val json = JSONObject().put(
            "quickActions", JSONArray()
                .put(check.toJson())
                .put(JSONObject().put("id", "custom-2").put("label", "Empty").put("prompt", "  "))
                .put(JSONObject().put("label", "No id").put("prompt", "x"))
                .put(JSONObject().put("id", "ask").put("label", "Ask").put("prompt", "x"))
                .put(check.toJson().put("label", "Duplicate"))
                .put("not an object")
        )
        assertEquals(listOf(check), AppSettings.fromJson(json).quickActions)
    }

    @Test
    fun `only Explain with its stock prompt asks about a few words directly`() {
        assertTrue(QuickAction.EXPLAIN.asksAboutShortText)
        assertTrue(QuickAction.EXPLAIN.edited("Tell me", QuickAction.EXPLAIN.prompt).asksAboutShortText)
        assertFalse(QuickAction.EXPLAIN.edited("Explain", "Explain in Spanish.").asksAboutShortText)
        assertFalse(check.asksAboutShortText)
    }

    @Test
    fun `shortcuts can still name a stock action the user deleted`() {
        val settings = AppSettings().withoutAction("translate")
        assertNull(settings.action("translate"))
        assertEquals(QuickAction.TRANSLATE, QuickAction.stock("translate"))
        assertEquals(QuickAction.ASK, settings.action("ask"))
    }
}
