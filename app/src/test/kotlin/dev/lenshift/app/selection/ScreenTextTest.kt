package dev.lenshift.app.selection

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ScreenTextTest {

    private fun w(text: String, l: Int, t: Int, r: Int, b: Int, line: Int) =
        ScreenWord(text, IntRect(l, t, r, b), line)

    /** Two text rows in a right-hand list, plus a label row in a left column. */
    private val raw = listOf(
        // OCR joined the left label and the right row into one line (line 0).
        w("Remote", 40, 940, 120, 960, 0),
        w("Desktop", 130, 940, 190, 960, 0),
        w("Redo", 620, 940, 680, 960, 0),
        w("all", 690, 940, 720, 960, 0),
        // Checkbox circle read as "O", then a task row (line 1), listed out of order.
        w("O", 580, 870, 600, 890, 1),
        w("lab", 660, 870, 700, 890, 1),
        w("3012", 620, 870, 655, 890, 1),
        // An icon read as a tall glyph, and pure punctuation.
        w("Y", 1150, 860, 1180, 960, 2),
        w("☆", 1150, 870, 1170, 890, 3)
    )

    private val words = ScreenText.normalize(raw)

    @Test
    fun `normalize drops noise, splits columns and orders visually`() {
        assertEquals(listOf("3012", "lab", "Redo", "all", "Remote", "Desktop"), words.map { it.text })
        // The list rows form one block (lines 0 and 1); the left label is its own line.
        assertEquals(listOf(0, 0, 1, 1, 2, 2), words.map { it.line })
    }

    @Test
    fun `selection text joins words and lines`() {
        assertEquals("3012 lab\nRedo all", ScreenText.text(words, 0..3))
        assertEquals(
            listOf(IntRect(620, 870, 700, 890), IntRect(620, 940, 720, 960)),
            ScreenText.highlights(words, 0..3)
        )
    }

    @Test
    fun `hit testing and handle snapping stay on the line`() {
        assertEquals(1, ScreenText.wordAt(words, 680f, 880f, 4f))
        assertNull(ScreenText.wordAt(words, 400f, 700f, 4f))
        // Just below "lab" and far to the right still snaps to the end of that row.
        assertEquals(1, ScreenText.nearestWord(words, 900f, 895f))
    }

    @Test
    fun `words inside a region form one range in its column`() {
        assertEquals(0..3, ScreenText.wordsIn(words, IntRect(600, 850, 760, 980)))
        assertNull(ScreenText.wordsIn(words, IntRect(0, 0, 10, 10)))
    }

    @Test
    fun `handles pin instead of inverting`() {
        assertEquals(5..5, ScreenText.moveHandle(2..5, isStart = true, index = 9))
        assertEquals(0..5, ScreenText.moveHandle(2..5, isStart = true, index = 0))
        assertEquals(2..2, ScreenText.moveHandle(2..5, isStart = false, index = 1))
        assertEquals(1..4, ScreenText.extend(anchor = 4, index = 1))
    }
}
