package dev.lenshift.app.ocr

import android.graphics.Rect
import android.graphics.Bitmap

/**
 * One recognised text run and where it sits in the *source* bitmap.
 *
 * Keeping coordinates means Lenshift can map OCR boxes back onto the screenshot for
 * tap-to-select, and can crop tightly before uploading.
 */
data class OcrWord(
    val text: String,
    val bounds: Rect,
    val confidence: Float
)

data class OcrLine(
    val text: String,
    val bounds: Rect,
    val words: List<OcrWord>
)

data class OcrBlock(
    val text: String,
    val bounds: Rect,
    val lines: List<OcrLine>
)

data class OcrResult(
    val blocks: List<OcrBlock>,
    val fullText: String,
    val languageTag: String,
    val durationMs: Long,
    /** True when the engine was not available and [fullText] is empty. */
    val unavailable: Boolean = false,
    val unavailableReason: String? = null
) {
    val isEmpty: Boolean get() = fullText.isBlank()

    /** Words whose centre lies inside [region], for tap or drag selection. */
    fun wordsIn(region: Rect): List<OcrWord> = blocks.asSequence()
        .flatMap { it.lines }
        .flatMap { it.words }
        .filter { word ->
            val cx = word.bounds.centerX()
            val cy = word.bounds.centerY()
            region.contains(cx, cy)
        }
        .toList()

    /** Rebuilds readable text from the words inside a region, preserving line breaks. */
    fun textIn(region: Rect): String {
        val words = wordsIn(region)
        if (words.isEmpty()) return ""

        // Group by the line each word came from so line breaks survive.
        val byLine = linkedMapOf<Int, MutableList<OcrWord>>()
        blocks.forEach { block ->
            block.lines.forEach { line ->
                line.words.forEach { word ->
                    if (region.contains(word.bounds.centerX(), word.bounds.centerY())) {
                        byLine.getOrPut(line.bounds.top) { mutableListOf() }.add(word)
                    }
                }
            }
        }
        return byLine.entries
            .sortedBy { it.key }
            .joinToString("\n") { (_, lineWords) ->
                lineWords.sortedBy { it.bounds.left }.joinToString(" ") { it.text }
            }
            .trim()
    }
}
