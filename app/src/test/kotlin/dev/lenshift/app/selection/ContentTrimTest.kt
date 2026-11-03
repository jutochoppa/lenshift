package dev.lenshift.app.selection

import org.junit.Assert.assertEquals
import org.junit.Test

class ContentTrimTest {

    private val white = 0xFFFFFFFF.toInt()
    private val black = 0xFF000000.toInt()

    /** A [w]x[h] image of [bg] with a [fg] block covering [block]. */
    private fun image(w: Int, h: Int, bg: Int, fg: Int, block: IntRect): IntArray =
        IntArray(w * h) { i ->
            val x = i % w
            val y = i / w
            if (block.contains(x, y)) fg else bg
        }

    @Test
    fun `plain margins around content are trimmed to a small margin`() {
        val px = image(200, 120, white, black, IntRect(60, 40, 140, 70))
        assertEquals(IntRect(56, 36, 144, 74), ContentTrim.contentBounds(px, 200, 120, margin = 4))
    }

    @Test
    fun `works on dark backgrounds and keeps the margin inside the crop`() {
        val px = image(100, 100, black, white, IntRect(2, 30, 98, 60))
        assertEquals(IntRect(0, 22, 100, 68), ContentTrim.contentBounds(px, 100, 100, margin = 8))
    }

    @Test
    fun `a busy image without plain margins is left alone`() {
        val px = IntArray(80 * 60) { i -> if ((i % 80 + i / 80) % 2 == 0) white else black }
        assertEquals(IntRect(0, 0, 80, 60), ContentTrim.contentBounds(px, 80, 60, margin = 4))
    }

    @Test
    fun `only background keeps the crop as drawn`() {
        val px = IntArray(50 * 50) { white }
        assertEquals(IntRect(0, 0, 50, 50), ContentTrim.contentBounds(px, 50, 50, margin = 4))
    }

    @Test
    fun `two-tone backgrounds, like a side panel beside a page, are both trimmed`() {
        val grey = 0xFFD9DBE0.toInt()
        val px = IntArray(300 * 100) { i -> if (i % 300 < 90) grey else white }
        for (y in 40 until 60) for (x in 150 until 230) px[y * 300 + x] = black
        assertEquals(IntRect(146, 36, 234, 64), ContentTrim.contentBounds(px, 300, 100, margin = 4))
    }

    @Test
    fun `slightly noisy background still counts as background`() {
        val noisy = IntArray(120 * 80) { i -> if (i % 7 == 0) 0xFFF4F4F4.toInt() else white }
        for (y in 30 until 50) for (x in 40 until 80) noisy[y * 120 + x] = black
        assertEquals(IntRect(38, 28, 82, 52), ContentTrim.contentBounds(noisy, 120, 80, margin = 2))
    }
}
