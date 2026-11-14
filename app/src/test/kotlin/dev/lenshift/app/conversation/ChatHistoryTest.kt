package dev.lenshift.app.conversation

import dev.lenshift.app.data.settings.AppSettings
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset

class ChatHistoryTest {

    private val zone: ZoneId = ZoneOffset.UTC
    private val now = LocalDateTime.of(2026, 10, 23, 12, 0).toInstant(ZoneOffset.UTC).toEpochMilli()
    private val day = 24L * 60 * 60 * 1000

    private fun thread(id: String, updatedAt: Long, vararg turns: ChatTurn) = ChatThread(
        id = id,
        createdAt = updatedAt,
        updatedAt = updatedAt,
        title = "t-$id",
        actionId = "explain",
        modelLabel = "model",
        sourceLabel = null,
        contextPrompt = "<screen_content>\nx\n</screen_content>\n\nExplain",
        hasImage = true,
        turns = turns.toList()
    )

    @Test
    fun `threads round trip through json`() {
        val original = listOf(
            thread(
                "a", now,
                ChatTurn("Explain", "It is a list.", "looked at it"),
                ChatTurn("Which is due?", "The email.", attachments = listOf("att-1", "att-2"))
            ),
            thread("b", now - day, ChatTurn("Translate", "Hola"))
        )
        assertEquals(original, ChatThread.decode(ChatThread.encode(original)))
    }

    @Test
    fun `damaged entries are skipped, not fatal`() {
        val good = thread("ok", now, ChatTurn("q", "a"))
        val text = JSONObject(ChatThread.encode(listOf(good))).apply {
            getJSONArray("threads").put("not an object").put(JSONObject().put("id", "")).put(
                JSONObject().put("id", "empty").put("turns", org.json.JSONArray())
            )
        }.toString()
        assertEquals(listOf(good), ChatThread.decode(text))
        assertTrue(ChatThread.decode("{broken").isEmpty())
    }

    @Test
    fun `titles use the typed question or the answer's first sentence`() {
        assertEquals("Which task is due first?", ChatHistoryFormat.title("Which task is due first?", true, "x"))
        assertEquals(
            "It's a to-do list app.",
            ChatHistoryFormat.title("Explain", false, "**It's a to-do list app.** It has seven items.")
        )
        val long = ChatHistoryFormat.title("Explain", false, "a".repeat(200))
        assertTrue(long.length <= 64 && long.endsWith("…"))
        assertEquals("Explain", ChatHistoryFormat.title("Explain", false, "```\ncode\n```"))
    }

    @Test
    fun `threads group by recency`() {
        val groups = ChatHistoryFormat.group(
            listOf(
                thread("old", now - 90 * day, ChatTurn("q", "a")),
                thread("today", now - 60_000, ChatTurn("q", "a")),
                thread("yesterday", now - day, ChatTurn("q", "a")),
                thread("week", now - 4 * day, ChatTurn("q", "a")),
                thread("month", now - 20 * day, ChatTurn("q", "a"))
            ),
            now,
            zone
        )
        assertEquals(
            listOf("Today", "Yesterday", "Previous 7 days", "Previous 30 days", "Older"),
            groups.map { it.first }
        )
        assertEquals(listOf("today"), groups.first().second.map { it.id })
    }

    @Test
    fun `ask is the neutral default action and the choice round trips`() {
        val ask = dev.lenshift.app.data.settings.QuickAction.ASK
        assertTrue(ask.prompt.isEmpty())
        assertEquals(ask, AppSettings.fromJson(JSONObject()).defaultQuickAction)
        assertEquals(ask, AppSettings.fromJson(JSONObject().put("defaultAction", "unknown")).defaultQuickAction)
        // The old key was saved automatically but never applied, so it is not carried over.
        assertEquals(ask, AppSettings.fromJson(JSONObject().put("defaultQuickAction", "explain")).defaultQuickAction)
        val solve = AppSettings.fromJson(JSONObject())
            .copy(defaultActionId = dev.lenshift.app.data.settings.QuickAction.SOLVE.id)
        assertEquals(dev.lenshift.app.data.settings.QuickAction.SOLVE, AppSettings.fromJson(solve.toJson()).defaultQuickAction)
    }

    @Test
    fun `search on select defaults off and round trips`() {
        assertFalse(AppSettings.fromJson(JSONObject()).searchOnSelect)
        val on = AppSettings.fromJson(JSONObject()).copy(searchOnSelect = true)
        assertTrue(AppSettings.fromJson(on.toJson()).searchOnSelect)
    }

    @Test
    fun `chat history defaults on and round trips`() {
        assertTrue(AppSettings.fromJson(JSONObject()).chatHistoryEnabled)
        val off = AppSettings.fromJson(JSONObject()).copy(chatHistoryEnabled = false)
        assertFalse(AppSettings.fromJson(off.toJson()).chatHistoryEnabled)
    }

    @Test
    fun `the newest recent chat is offered to continue`() {
        val minute = 60_000L
        val recent = thread("new", now - 5 * minute)
        val older = thread("old", now - 3 * day)
        assertEquals("new", ChatHistoryFormat.resumable(listOf(older, recent), now)?.id)
        assertEquals(null, ChatHistoryFormat.resumable(listOf(older), now))
        assertEquals(null, ChatHistoryFormat.resumable(emptyList(), now))
        // A chat from just inside the window still counts; one just outside does not.
        assertEquals("edge", ChatHistoryFormat.resumable(listOf(thread("edge", now - day)), now)?.id)
        assertEquals(null, ChatHistoryFormat.resumable(listOf(thread("late", now - day - 1)), now))
    }

    @Test
    fun `how long ago reads short`() {
        val minute = 60_000L
        assertEquals("now", ChatHistoryFormat.ago(now - 20_000, now))
        assertEquals("now", ChatHistoryFormat.ago(now + 5 * minute, now))
        assertEquals("5m", ChatHistoryFormat.ago(now - 5 * minute, now))
        assertEquals("2h", ChatHistoryFormat.ago(now - 125 * minute, now))
        assertEquals("yesterday", ChatHistoryFormat.ago(now - 30 * 60 * minute, now))
        assertEquals("3d", ChatHistoryFormat.ago(now - 3 * day, now))
    }
}
