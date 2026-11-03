package dev.lenshift.app.selection

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Shrinks a crop to its content by removing plain margins.
 *
 * People circle loosely, so a circle's bounding box holds a lot of empty background around
 * the thing they meant. Any edge row or column that is a single flat colour is margin, so
 * edges move inward while that holds, alternating rows and columns until nothing changes
 * (a side panel next to a page is two flat colours: trimming its columns first lets the
 * rows trim next). A small margin is then added back. Photos and busy areas have no flat
 * edges and are left as they are.
 *
 * Framework-free: works on ARGB pixels so it is unit-tested.
 */
object ContentTrim {

    /**
     * @param pixels the crop's pixels, row-major, [width] by [height] (ARGB).
     * @param margin pixels kept around the content.
     * @return the content bounds inside the crop, in crop coordinates.
     */
    fun contentBounds(
        pixels: IntArray,
        width: Int,
        height: Int,
        margin: Int,
        tolerance: Int = DEFAULT_TOLERANCE
    ): IntRect {
        val full = IntRect(0, 0, width, height)
        if (width < MIN_SIZE || height < MIN_SIZE || pixels.size < width * height) return full

        // Margin colours are the ones the user's loose crop edge runs through.
        val background = edgeColours(pixels, width, height)

        var left = 0
        var top = 0
        var right = width
        var bottom = height

        fun rowFlat(y: Int) = isMargin(right - left, background, tolerance) { i -> pixels[y * width + left + i] }
        fun columnFlat(x: Int) = isMargin(bottom - top, background, tolerance) { i -> pixels[(top + i) * width + x] }

        var changed = true
        while (changed) {
            changed = false
            while (bottom - top > MIN_SIZE && rowFlat(top)) { top++; changed = true }
            while (bottom - top > MIN_SIZE && rowFlat(bottom - 1)) { bottom--; changed = true }
            while (right - left > MIN_SIZE && columnFlat(left)) { left++; changed = true }
            while (right - left > MIN_SIZE && columnFlat(right - 1)) { right--; changed = true }
        }

        // Nothing but flat colour inside: keep the crop the user drew.
        if (right - left <= MIN_SIZE && bottom - top <= MIN_SIZE) return full

        return IntRect(
            max(0, left - margin),
            max(0, top - margin),
            min(width, right + margin),
            min(height, bottom + margin)
        )
    }

    /**
     * Colours making up a fair share (15 %+) of the crop's border: the background, or two
     * backgrounds such as a side panel beside a page. The most common is always included.
     */
    internal fun edgeColours(pixels: IntArray, width: Int, height: Int): List<Int> {
        val counts = HashMap<Int, Int>()
        val firstSeen = HashMap<Int, Int>()
        var total = 0
        fun add(p: Int) {
            val key = p shr 4 and 0x0F0F0F
            counts[key] = (counts[key] ?: 0) + 1
            firstSeen.putIfAbsent(key, p)
            total++
        }
        val step = max(1, max(width, height) / 200)
        for (x in 0 until width step step) {
            add(pixels[x])
            add(pixels[(height - 1) * width + x])
        }
        for (y in 0 until height step step) {
            add(pixels[y * width])
            add(pixels[y * width + width - 1])
        }
        val ranked = counts.entries.sortedByDescending { it.value }
        return ranked.filterIndexed { i, e -> i == 0 || e.value * 100 >= total * 15 }
            .mapNotNull { firstSeen[it.key] }
    }

    /**
     * True when all but about 1 % of the line's pixels share one colour (within [tol]) and
     * that colour is one of the [background] colours. Two reference pixels are tried so a
     * single stray pixel cannot hide a flat line.
     */
    private inline fun isMargin(length: Int, background: List<Int>, tol: Int, pixelAt: (Int) -> Int): Boolean {
        if (length <= 0) return false
        val allowed = length / 100
        for (reference in intArrayOf(pixelAt(0), pixelAt(length / 2))) {
            if (background.none { close(reference, it, tol) }) continue
            var mismatches = 0
            var flat = true
            for (i in 0 until length) {
                if (!close(pixelAt(i), reference, tol) && ++mismatches > allowed) {
                    flat = false
                    break
                }
            }
            if (flat) return true
        }
        return false
    }

    private fun close(a: Int, b: Int, tol: Int): Boolean =
        abs((a shr 16 and 0xFF) - (b shr 16 and 0xFF)) <= tol &&
            abs((a shr 8 and 0xFF) - (b shr 8 and 0xFF)) <= tol &&
            abs((a and 0xFF) - (b and 0xFF)) <= tol

    private const val MIN_SIZE = 16
    private const val DEFAULT_TOLERANCE = 18
}
