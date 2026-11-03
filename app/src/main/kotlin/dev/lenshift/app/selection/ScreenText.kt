package dev.lenshift.app.selection

import kotlin.math.max
import kotlin.math.min

/** One OCR word on the captured screen, in source-bitmap pixels, with its line number. */
data class ScreenWord(val text: String, val bounds: IntRect, val line: Int)

/**
 * Text-selection geometry over the words recognised on the whole screen.
 *
 * Words are kept in reading order (the order the OCR engine reports them), so a selection
 * is simply an index range. Everything here is framework-free and unit-tested.
 */
object ScreenText {

    /** The word under ([x], [y]), allowing [slop] pixels of tolerance around each box. */
    fun wordAt(words: List<ScreenWord>, x: Float, y: Float, slop: Float): Int? {
        var best: Int? = null
        var bestDistance = Float.MAX_VALUE
        words.forEachIndexed { i, w ->
            val b = w.bounds
            val dx = when {
                x < b.left -> b.left - x
                x > b.right -> x - b.right
                else -> 0f
            }
            val dy = when {
                y < b.top -> b.top - y
                y > b.bottom -> y - b.bottom
                else -> 0f
            }
            if (dx <= slop && dy <= slop) {
                val d = dx + dy
                if (d < bestDistance) {
                    bestDistance = d
                    best = i
                }
            }
        }
        return best
    }

    /**
     * The word a selection handle should snap to: the closest word box, where vertical
     * distance counts three times as much as horizontal so the handle stays on its line.
     */
    fun nearestWord(words: List<ScreenWord>, x: Float, y: Float): Int? =
        words.indices.minByOrNull { i ->
            val b = words[i].bounds
            val dx = when {
                x < b.left -> b.left - x
                x > b.right -> x - b.right
                else -> 0f
            }
            val dy = when {
                y < b.top -> b.top - y
                y > b.bottom -> y - b.bottom
                else -> 0f
            }
            dy * 3f + dx
        }

    /**
     * Cleans raw OCR words for selection and puts them in visual reading order.
     *
     * Drops tokens with no letters or digits, boxes far taller than typical text, and
     * isolated square single characters (checkbox circles read as "O"). Lines are split at
     * column gaps, then clustered into text blocks (horizontally overlapping, at most 2.5
     * line heights apart, as list rows usually are). Blocks are ordered top to bottom then
     * left to right, lines within a block top to bottom, words left to right, so a
     * selection range stays inside one column.
     */
    fun normalize(words: List<ScreenWord>): List<ScreenWord> {
        val textual = words.filter { w -> w.text.any { it.isLetterOrDigit() } }
        if (textual.isEmpty()) return emptyList()
        val heights = textual.map { it.bounds.height }.sorted()
        val median = heights[heights.size / 2].coerceAtLeast(1)
        val sized = textual.filter { it.bounds.height <= median * 2.5f }

        val lines = sized.groupBy { it.line }.values
            .map { line -> line.sortedBy { it.bounds.left }.filterNot { isGlyphNoise(it, line) } }
            .flatMap(::splitAtGaps)
            .filter { it.isNotEmpty() }
            .sortedBy { line -> line.minOf { it.bounds.top } }

        val blocks = mutableListOf<MutableList<List<ScreenWord>>>()
        for (line in lines) {
            val box = boundsOf(line)
            val block = blocks.lastOrNull { block ->
                val last = boundsOf(block.last())
                val overlap = min(last.right, box.right) - max(last.left, box.left)
                val gap = box.top - last.bottom
                overlap > 0 && gap <= max(last.height, box.height) * 2.5f
            }
            if (block != null) block += line else blocks += mutableListOf(line)
        }

        return blocks
            .sortedWith(compareBy({ b -> boundsOf(b.first()).top / max(median, 1) }, { b -> boundsOf(b.first()).left }))
            .flatMap { it }
            .flatMapIndexed { index, line -> line.map { it.copy(line = index) } }
    }

    /**
     * OCR sometimes joins text from side-by-side columns into one line. A horizontal gap
     * wider than 2.5 line heights is a column break, never a word space.
     */
    private fun splitAtGaps(line: List<ScreenWord>): List<List<ScreenWord>> {
        if (line.size < 2) return listOf(line)
        val height = line.maxOf { it.bounds.height }
        val parts = mutableListOf(mutableListOf(line.first()))
        for (i in 1 until line.size) {
            if (line[i].bounds.left - line[i - 1].bounds.right > height * 2.5f) parts += mutableListOf<ScreenWord>()
            parts.last() += line[i]
        }
        return parts
    }

    /** A lone, roughly square single character set apart from the next word: an icon. */
    private fun isGlyphNoise(word: ScreenWord, line: List<ScreenWord>): Boolean {
        if (word.text.length != 1 || word.text[0] !in "Oo0Q@©®") return false
        val b = word.bounds
        val aspect = b.width.toFloat() / b.height.coerceAtLeast(1)
        if (aspect < 0.7f || aspect > 1.4f) return false
        val next = line.firstOrNull { it.bounds.left > b.right } ?: return true
        return next.bounds.left - b.right > b.width * 0.8f
    }

    private fun boundsOf(line: List<ScreenWord>): IntRect = IntRect(
        line.minOf { it.bounds.left },
        line.minOf { it.bounds.top },
        line.maxOf { it.bounds.right },
        line.maxOf { it.bounds.bottom }
    )

    /** One rectangle per line covering the selected words on that line. */
    fun highlights(words: List<ScreenWord>, range: IntRange): List<IntRect> {
        val selected = range.filter { it in words.indices }.map { words[it] }
        return selected.groupBy { it.line }.values.map { lineWords ->
            IntRect(
                lineWords.minOf { it.bounds.left },
                lineWords.minOf { it.bounds.top },
                lineWords.maxOf { it.bounds.right },
                lineWords.maxOf { it.bounds.bottom }
            )
        }
    }

    /** Selected text: words joined by spaces within a line, lines by newlines. */
    fun text(words: List<ScreenWord>, range: IntRange): String {
        val selected = range.filter { it in words.indices }.map { words[it] }
        return selected.groupBy { it.line }.values.joinToString("\n") { line ->
            line.joinToString(" ") { it.text }
        }
    }

    /** Indices of the words whose centres fall inside [rect], as one reading-order range. */
    fun wordsIn(words: List<ScreenWord>, rect: IntRect): IntRange? {
        val inside = words.indices.filter { i ->
            val b = words[i].bounds
            rect.contains(b.centerX, b.centerY)
        }
        return if (inside.isEmpty()) null else inside.first()..inside.last()
    }

    /** The bounds of the whole line containing word [index]. */
    fun lineBounds(words: List<ScreenWord>, index: Int): IntRect {
        val line = words[index].line
        val lineWords = words.filter { it.line == line }
        return IntRect(
            lineWords.minOf { it.bounds.left },
            lineWords.minOf { it.bounds.top },
            lineWords.maxOf { it.bounds.right },
            lineWords.maxOf { it.bounds.bottom }
        )
    }

    /** Extends a selection anchored at [anchor] to [index], in either direction. */
    fun extend(anchor: Int, index: Int): IntRange = min(anchor, index)..max(anchor, index)

    /**
     * Moves one end of [range] to [index]. A start handle dragged past the end (or vice
     * versa) pins to the other end rather than inverting the selection.
     */
    fun moveHandle(range: IntRange, isStart: Boolean, index: Int): IntRange =
        if (isStart) min(index, range.last)..range.last
        else range.first..max(index, range.first)
}
