package dev.lenshift.app.ui.capture

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import dev.lenshift.app.selection.IntRect
import dev.lenshift.app.selection.PointF2
import org.junit.Assert.assertEquals
import org.junit.Test

class ScreenshotCanvasGeometryTest {

    @Test
    fun `portrait bitmap letterboxed in square canvas maps center both ways`() {
        val fit = computeFitRect(1000f, 1000f, 1080, 2400)
        assertEquals(Rect(275f, 0f, 725f, 1000f), fit)

        val source = PointF2(540f, 1200f)
        val canvas = sourceToCanvas(source, fit, 1080, 2400)
        assertEquals(Offset(500f, 500f), canvas)
        assertEquals(source, canvasToSource(canvas, fit, 1080, 2400))
    }

    @Test
    fun `landscape bitmap letterboxed in square canvas maps center both ways`() {
        val fit = computeFitRect(1000f, 1000f, 2400, 1080)
        assertEquals(Rect(0f, 275f, 1000f, 725f), fit)

        val source = PointF2(1200f, 540f)
        val canvas = sourceToCanvas(source, fit, 2400, 1080)
        assertEquals(Offset(500f, 500f), canvas)
        assertEquals(source, canvasToSource(canvas, fit, 2400, 1080))
    }

    @Test
    fun `canvas points in letterbox are not inside the image`() {
        val fit = computeFitRect(1000f, 1000f, 1080, 2400)
        assertEquals(275f, fit.left, 0.001f)
        assertEquals(725f, fit.right, 0.001f)
    }

    @Test
    fun `rectangle canvas mapping uses source scale and letterbox offset`() {
        val fit = computeFitRect(1000f, 1000f, 1080, 2400)
        val topLeft = sourceToCanvas(PointF2(108f, 480f), fit, 1080, 2400)
        val bottomRight = sourceToCanvas(PointF2(972f, 1920f), fit, 1080, 2400)

        assertEquals(320f, topLeft.x, 0.001f)
        assertEquals(200f, topLeft.y, 0.001f)
        assertEquals(680f, bottomRight.x, 0.001f)
        assertEquals(800f, bottomRight.y, 0.001f)
    }

    @Test
    fun `corner resize keeps the opposite corner fixed and a minimum size`() {
        val fixed = PointF2(100f, 100f)
        assertEquals(IntRect(40, 30, 100, 100), resizedRect(fixed, PointF2(40f, 30f), 24, 1000, 1000))
        // Dragged almost onto the fixed corner: grows to the minimum toward the finger.
        assertEquals(IntRect(100, 100, 124, 124), resizedRect(fixed, PointF2(101f, 102f), 24, 1000, 1000))
        // Clamped to the image.
        assertEquals(IntRect(100, 100, 1000, 800), resizedRect(fixed, PointF2(1500f, 800f), 24, 1000, 1000))
    }
}
