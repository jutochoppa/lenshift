package dev.lenshift.app.selection

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Geometry tests.
 *
 * These run on the JVM with no Android framework: [SelectionGeometry] was written to keep
 * its maths framework-free precisely so the crop logic can be verified deterministically.
 */
class SelectionGeometryTest {

    @Test
    fun `lasso bounds cover the stroke`() {
        val points = listOf(
            PointF2(100f, 100f),
            PointF2(300f, 100f),
            PointF2(300f, 260f),
            PointF2(100f, 260f)
        )
        val rect = SelectionGeometry.lassoBounds(points, 1080, 2400)

        assertTrue("left should be near 100", rect.left in 90..100)
        assertTrue("top should be near 100", rect.top in 90..100)
        assertTrue("right should be near 300", rect.right in 300..312)
        assertTrue("bottom should be near 260", rect.bottom in 260..272)
    }

    @Test
    fun `lasso pads a loose circle so content inside is kept`() {
        // A circle of radius 100 centred at (500, 500).
        val points = (0 until 72).map { i ->
            val angle = Math.toRadians(i * 5.0)
            PointF2(
                (500 + 100 * Math.cos(angle)).toFloat(),
                (500 + 100 * Math.sin(angle)).toFloat()
            )
        }
        val rect = SelectionGeometry.lassoBounds(points, 1080, 2400)

        assertTrue("padded box must be wider than the stroke", rect.width > 200)
        assertTrue("padded box must be taller than the stroke", rect.height > 200)
    }

    @Test
    fun `a dot becomes a tap lens instead of an empty crop`() {
        val rect = SelectionGeometry.lassoBounds(
            listOf(PointF2(500f, 500f), PointF2(501f, 501f)),
            1080,
            2400
        )

        assertTrue("must have real area", rect.width > 24)
        assertTrue("must have real area", rect.height > 24)
        assertTrue("should be centred on the tap", rect.contains(500, 500))
    }

    @Test
    fun `lasso is clamped to the image`() {
        val points = listOf(
            PointF2(-500f, -500f),
            PointF2(5000f, 5000f)
        )
        val rect = SelectionGeometry.lassoBounds(points, 1080, 2400)

        assertEquals(0, rect.left)
        assertEquals(0, rect.top)
        assertEquals(1080, rect.right)
        assertEquals(2400, rect.bottom)
    }

    @Test
    fun `rectangle from drag normalises a backwards drag`() {
        val rect = SelectionGeometry.rectangleFromDrag(400f, 600f, 100f, 200f)

        assertEquals(100, rect.left)
        assertEquals(200, rect.top)
        assertEquals(400, rect.right)
        assertEquals(600, rect.bottom)
    }

    @Test
    fun `tap bounds are square and inside the image`() {
        val rect = SelectionGeometry.tapBounds(5f, 5f, 1080, 2400)

        assertEquals("tap lens should be square", rect.width, rect.height)
        assertTrue(rect.left >= 0)
        assertTrue(rect.top >= 0)
        assertTrue(rect.right <= 1080)
        assertTrue(rect.bottom <= 2400)
    }

    @Test
    fun `tap lens stays local instead of silently selecting the full screen`() {
        val width = 1080
        val height = 2400
        val lens = SelectionGeometry.tapBounds(540f, 1200f, width, height)

        assertTrue(lens.contains(540, 1200))
        assertTrue(lens.width < width / 2)
        assertTrue(lens.height < height / 2)
        assertTrue(
            "a tap miss must upload only the lens, never the whole screen",
            SelectionGeometry.areaFraction(lens, width, height) < 0.1f
        )
    }

    @Test
    fun `tap lens remains bounded at all four corners`() {
        val points = listOf(
            PointF2(0f, 0f),
            PointF2(1079f, 0f),
            PointF2(0f, 2399f),
            PointF2(1079f, 2399f)
        )
        points.forEach { point ->
            val lens = SelectionGeometry.tapBounds(point.x, point.y, 1080, 2400)
            assertTrue(lens.left >= 0)
            assertTrue(lens.top >= 0)
            assertTrue(lens.right <= 1080)
            assertTrue(lens.bottom <= 2400)
            assertTrue(lens.width > 0 && lens.height > 0)
        }
    }

    @Test
    fun `simplify removes points closer than the threshold`() {
        val points = listOf(
            PointF2(0f, 0f),
            PointF2(0.5f, 0f),
            PointF2(5f, 0f),
            PointF2(5.5f, 0f),
            PointF2(10f, 0f)
        )
        val simplified = SelectionGeometry.simplify(points, minDistancePx = 2f)

        assertTrue("should drop near-duplicate points", simplified.size < points.size)
        assertEquals("must keep the first point", points.first(), simplified.first())
        assertEquals("must keep the last point", points.last(), simplified.last())
    }

    @Test
    fun `self intersection detects a scribble`() {
        // A figure-eight-ish stroke that crosses itself.
        val scribble = listOf(
            PointF2(0f, 0f),
            PointF2(100f, 100f),
            PointF2(100f, 0f),
            PointF2(0f, 100f),
            PointF2(0f, 0f),
            PointF2(50f, 50f),
            PointF2(60f, 40f)
        )
        assertTrue(SelectionGeometry.isSelfIntersecting(scribble))
    }

    @Test
    fun `a simple circle is not self intersecting`() {
        val circle = (0 until 60).map { i ->
            val angle = Math.toRadians(i * 6.0)
            PointF2(
                (500 + 100 * Math.cos(angle)).toFloat(),
                (500 + 100 * Math.sin(angle)).toFloat()
            )
        }
        assertFalse(SelectionGeometry.isSelfIntersecting(circle))
    }

    @Test
    fun `contains point works for a square polygon`() {
        val square = listOf(
            PointF2(0f, 0f),
            PointF2(100f, 0f),
            PointF2(100f, 100f),
            PointF2(0f, 100f)
        )
        assertTrue(SelectionGeometry.containsPoint(square, 50f, 50f))
        assertFalse(SelectionGeometry.containsPoint(square, 150f, 50f))
    }

    @Test
    fun `too thin flags an unusable crop`() {
        assertTrue(SelectionGeometry.isTooThin(IntRect(0, 0, 10, 10)))
        assertFalse(SelectionGeometry.isTooThin(IntRect(0, 0, 100, 100)))
    }

    @Test
    fun `area fraction is bounded`() {
        val full = SelectionGeometry.areaFraction(
            IntRect(0, 0, 100, 100), 100, 100
        )
        assertEquals(1.0f, full, 0.001f)

        val quarter = SelectionGeometry.areaFraction(
            IntRect(0, 0, 50, 50), 100, 100
        )
        assertEquals(0.25f, quarter, 0.001f)
    }
}
