package dev.lenshift.app.selection

import android.graphics.Path
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * How the user chose the region.
 */
enum class SelectionMode {
    /** Freehand circle or scribble. */
    LASSO,

    /** Axis-aligned rectangle with draggable handles. */
    RECTANGLE,

    /** Single tap: resolves to the OCR word under the finger, or a small lens area. */
    TAP,

    /** The whole screenshot. */
    FULL;

    companion object {
        fun fromId(id: String?): SelectionMode =
            entries.firstOrNull { it.name.equals(id, true) } ?: LASSO
    }
}

/**
 * Pure geometry for turning a raw gesture into a crop rectangle.
 *
 * Design decisions:
 *  - A hand-drawn "circle" is never treated as a mathematical circle. The bounding box of
 *    the stroke is used, then expanded slightly, because people circle things loosely and
 *    the content usually sits inside the loop.
 *  - A scribble (a stroke that crosses itself) is treated as a highlight: the bounding box
 *    of the stroke is the region, because the user was underlining rather than enclosing.
 *  - Degenerate strokes (a dot, a 2 px drag) fall back to a tap-sized lens so the user
 *    still gets a usable crop instead of an empty one.
 *
 * Everything here is deterministic and, apart from [Path] construction, free of Android
 * framework calls — it works in terms of [IntRect] and primitive floats, so the crop logic is
 * verifiable in local JVM unit tests.
 */
object SelectionGeometry {

    /** Smallest crop, in source pixels, that is worth OCR-ing or uploading. */
    const val MIN_REGION_PX = 24

    /** How much to pad a lasso's bounding box, as a fraction of its longest edge. */
    private const val LASSO_PADDING_FRACTION = 0.04f

    /** Radius of the tap lens, as a fraction of the shorter screen edge. */
    private const val TAP_LENS_FRACTION = 0.12f

    /**
     * Bounding box of a freehand stroke, padded and clamped to the image.
     *
     * @param points stroke points in source-image coordinates.
     */
    fun lassoBounds(
        points: List<PointF2>,
        imageWidth: Int,
        imageHeight: Int
    ): IntRect {
        if (points.isEmpty()) {
            return IntRect(0, 0, imageWidth, imageHeight)
        }

        var minX = Float.MAX_VALUE
        var minY = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE
        var maxY = -Float.MAX_VALUE

        points.forEach { p ->
            if (p.x < minX) minX = p.x
            if (p.y < minY) minY = p.y
            if (p.x > maxX) maxX = p.x
            if (p.y > maxY) maxY = p.y
        }

        val width = maxX - minX
        val height = maxY - minY

        // A dot or a tiny drag is really a tap.
        if (width < MIN_REGION_PX || height < MIN_REGION_PX) {
            return tapBounds((minX + maxX) / 2f, (minY + maxY) / 2f, imageWidth, imageHeight)
        }

        val pad = max(width, height) * LASSO_PADDING_FRACTION
        return clamp(
            minX - pad, minY - pad, maxX + pad, maxY + pad,
            imageWidth,
            imageHeight
        )
    }

    /**
     * True when a stroke crosses itself, which means the user was scribbling/highlighting
     * rather than enclosing. Used only to adjust the UI hint, not the geometry.
     */
    fun isSelfIntersecting(points: List<PointF2>): Boolean {
        if (points.size < 6) return false
        // Sample to keep this O(n^2) check cheap on long strokes.
        val step = max(1, points.size / 120)
        val sampled = points.filterIndexed { index, _ -> index % step == 0 }
        if (sampled.size < 6) return false

        for (i in 0 until sampled.size - 2) {
            for (j in i + 2 until sampled.size - 1) {
                // Skip the wrap-around pair that shares an endpoint.
                if (i == 0 && j == sampled.size - 2) continue
                if (segmentsIntersect(
                        sampled[i], sampled[i + 1], sampled[j], sampled[j + 1]
                    )
                ) {
                    return true
                }
            }
        }
        return false
    }

    /** A square lens centred on a tap, sized relative to the screen. */
    fun tapBounds(
        x: Float,
        y: Float,
        imageWidth: Int,
        imageHeight: Int
    ): IntRect {
        val radius = (min(imageWidth, imageHeight) * TAP_LENS_FRACTION)
            .coerceAtLeast(MIN_REGION_PX.toFloat())
        return clamp(
            x - radius, y - radius, x + radius, y + radius,
            imageWidth,
            imageHeight
        )
    }

    /** Normalises a drag into a positive-area rectangle. */
    fun rectangleFromDrag(startX: Float, startY: Float, endX: Float, endY: Float): IntRect =
        IntRect(
            min(startX, endX).toInt(),
            min(startY, endY).toInt(),
            max(startX, endX).toInt(),
            max(startY, endY).toInt()
        )

    /**
     * Clamps a float rectangle into the image and returns an integer rectangle.
     *
     * Takes four floats rather than a `RectF` because `RectF` is a stub in local JVM unit
     * tests — keeping this arithmetic on primitives is what makes the crop logic testable.
     */
    fun clamp(
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
        width: Int,
        height: Int
    ): IntRect {
        if (width <= 0 || height <= 0) return IntRect.EMPTY

        val l = left.coerceIn(0f, width.toFloat())
        val t = top.coerceIn(0f, height.toFloat())
        val r = right.coerceIn(0f, width.toFloat())
        val b = bottom.coerceIn(0f, height.toFloat())

        val result = IntRect(
            l.toInt().coerceIn(0, max(0, width - 1)),
            t.toInt().coerceIn(0, max(0, height - 1)),
            r.toInt().coerceIn(1, width),
            b.toInt().coerceIn(1, height)
        )

        // Guarantee at least a 1 px region so createBitmap never throws.
        return if (result.width < 1 || result.height < 1) {
            IntRect(0, 0, width, height)
        } else {
            result
        }
    }

    /** Clamps an integer rectangle into the image. */
    fun clamp(rect: IntRect, width: Int, height: Int): IntRect =
        clamp(
            rect.left.toFloat(),
            rect.top.toFloat(),
            rect.right.toFloat(),
            rect.bottom.toFloat(),
            width,
            height
        )

    /** Builds an SVG-style path string for the lasso so it can be replayed. */
    fun toSvgPath(points: List<PointF2>): String {
        if (points.isEmpty()) return ""
        val sb = StringBuilder()
        points.forEachIndexed { index, p ->
            sb.append(if (index == 0) "M" else "L")
            sb.append(p.x).append(',').append(p.y).append(' ')
        }
        return sb.toString().trim()
    }

    fun toPath(points: List<PointF2>): Path {
        val path = Path()
        points.forEachIndexed { index, p ->
            if (index == 0) path.moveTo(p.x, p.y) else path.lineTo(p.x, p.y)
        }
        return path
    }

    /** Decimates a dense stroke so rendering and replay stay cheap. */
    fun simplify(points: List<PointF2>, minDistancePx: Float = 2f): List<PointF2> {
        if (points.size <= 2) return points
        val out = mutableListOf(points.first())
        points.forEach { p ->
            val last = out.last()
            if (hypot(p.x - last.x, p.y - last.y) >= minDistancePx) out.add(p)
        }
        if (out.last() != points.last()) out.add(points.last())
        return out
    }

    // ---------------------------------------------------------------- helpers

    private fun segmentsIntersect(a1: PointF2, a2: PointF2, b1: PointF2, b2: PointF2): Boolean {
        val d1 = cross(b1, b2, a1)
        val d2 = cross(b1, b2, a2)
        val d3 = cross(a1, a2, b1)
        val d4 = cross(a1, a2, b2)
        return ((d1 > 0 && d2 < 0) || (d1 < 0 && d2 > 0)) &&
            ((d3 > 0 && d4 < 0) || (d3 < 0 && d4 > 0))
    }

    private fun cross(o: PointF2, a: PointF2, b: PointF2): Float =
        (a.x - o.x) * (b.y - o.y) - (a.y - o.y) * (b.x - o.x)

    /** Point-in-polygon test, used for lasso-mask support. */
    fun containsPoint(polygon: List<PointF2>, x: Float, y: Float): Boolean {
        if (polygon.size < 3) return false
        var inside = false
        var j = polygon.size - 1
        for (i in polygon.indices) {
            val pi = polygon[i]
            val pj = polygon[j]
            if ((pi.y > y) != (pj.y > y) &&
                x < (pj.x - pi.x) * (y - pi.y) / (pj.y - pi.y) + pi.x
            ) {
                inside = !inside
            }
            j = i
        }
        return inside
    }

    /** True when the crop is too small to read anything useful. */
    fun isTooThin(rect: IntRect): Boolean =
        rect.width < MIN_REGION_PX || rect.height < MIN_REGION_PX

    /** Fraction of the screenshot the crop covers. */
    fun areaFraction(rect: IntRect, imageWidth: Int, imageHeight: Int): Float {
        val total = (imageWidth.toLong() * imageHeight).coerceAtLeast(1)
        return rect.area / total.toFloat()
    }

    fun roughlySquare(rect: IntRect, tolerance: Float = 0.35f): Boolean {
        if (rect.height == 0) return false
        val ratio = rect.width.toFloat() / rect.height
        return abs(ratio - 1f) <= tolerance
    }
}

/** Minimal immutable 2-D point, kept framework-free for testability. */
data class PointF2(val x: Float, val y: Float)
