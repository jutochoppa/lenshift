package dev.lenshift.app.ui.visual

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.lenshift.app.visual.Camera3
import dev.lenshift.app.visual.Dash
import dev.lenshift.app.visual.LabelKind
import dev.lenshift.app.visual.MathText
import dev.lenshift.app.visual.Scene3D
import dev.lenshift.app.visual.Scene3DBuilder
import dev.lenshift.app.visual.SceneMesh
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * A 3D scene: shaded faces, lines and points sorted far to near, lit from the viewer's
 * upper left, with labels on top. Dragging orbits, pinching zooms, a double tap resets the
 * view. While a gesture runs, a coarser mesh keeps rotation smooth.
 */
@Composable
fun Graph3DCanvas(scene: Scene3D, state: VisualState, modifier: Modifier = Modifier) {
    val measurer = rememberTextMeasurer(cacheSize = 96)
    val initial = remember(scene) { scene.params.map { it.value }.toDoubleArray() }
    val box = remember(scene) { Scene3DBuilder.autoBox(scene, initial) }
    val sliders = state.sliders.toList()
    val fine = remember(scene, sliders, box) { Scene3DBuilder.build(scene, sliders.toDoubleArray(), box, resolution = 40) }
    val coarse = remember(scene, sliders, box) { lazy { Scene3DBuilder.build(scene, sliders.toDoubleArray(), box, resolution = 20) } }
    val mesh = if (state.interacting) coarse.value else fine
    SideEffect {
        state.legend = fine.legend
        state.runtimeNotes = fine.notes
    }
    val camera = state.camera

    Canvas(
        modifier
            .pointerInput(scene) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    var moving = false
                    var travelled = Offset.Zero
                    do {
                        val event = awaitPointerEvent()
                        val zoom = event.calculateZoom()
                        val pan = event.calculatePan()
                        if (!moving) {
                            travelled += pan
                            if (travelled.getDistance() > viewConfiguration.touchSlop || abs(zoom - 1f) > 0.02f) {
                                moving = true
                                state.interacting = true
                            }
                        }
                        if (moving) {
                            var cam = state.camera
                            if (zoom != 1f) cam = cam.zoomed(zoom.toDouble())
                            // The front of the model follows the finger.
                            cam = cam.rotated(-pan.x * 0.008, pan.y * 0.008)
                            state.camera = cam
                            event.changes.forEach { if (it.positionChanged()) it.consume() }
                        }
                    } while (event.changes.any { it.pressed })
                    state.interacting = false
                }
            }
            .pointerInput(scene) { detectTapGestures(onDoubleTap = { state.resetView() }) }
    ) {
        if (size.width <= 0f || size.height <= 0f) return@Canvas
        clipRect { drawScene(mesh, camera, measurer) }
    }
}

private val TEXT = Color(0xFFE8EAED)
private val MUTED = Color(0xFF9AA0A6)

private fun DrawScope.drawScene(mesh: SceneMesh, cam: Camera3, measurer: TextMeasurer) {
    val w = size.width
    val h = size.height
    // Light from the viewer's upper left.
    var lx = cam.fx * 0.75f - cam.right[0] * 0.35f + cam.up[0] * 0.55f
    var ly = cam.fy * 0.75f - cam.right[1] * 0.35f + cam.up[1] * 0.55f
    var lz = cam.fz * 0.75f - cam.right[2] * 0.35f + cam.up[2] * 0.55f
    val ll = sqrt(lx * lx + ly * ly + lz * lz)
    lx /= ll
    ly /= ll
    lz /= ll

    val faces = mesh.faces
    val segs = mesh.segs
    val dots = mesh.dots
    val nf = faces.size
    val ns = segs.size
    val nd = dots.size
    val depth = FloatArray(nf + ns + nd)
    val faceXY = arrayOfNulls<FloatArray>(nf)
    for (i in 0 until nf) {
        val f = faces[i]
        val k = f.xyz.size / 3
        val xy = FloatArray(k * 2)
        var sum = 0f
        for (j in 0 until k) sum += cam.project(f.xyz[3 * j], f.xyz[3 * j + 1], f.xyz[3 * j + 2], w, h, xy, 2 * j)
        faceXY[i] = xy
        depth[i] = sum / k
    }
    val segXY = FloatArray(ns * 4)
    for (i in 0 until ns) {
        val s = segs[i]
        val a = cam.project(s.ax, s.ay, s.az, w, h, segXY, 4 * i)
        val b = cam.project(s.bx, s.by, s.bz, w, h, segXY, 4 * i + 2)
        // A hair in front of faces in the same place, so edges and axes stay visible.
        depth[nf + i] = (a + b) / 2 + 0.004f
    }
    val dotXY = FloatArray(nd * 2)
    for (i in 0 until nd) {
        val d = dots[i]
        depth[nf + ns + i] = cam.project(d.x, d.y, d.z, w, h, dotXY, 2 * i) + 0.01f
    }
    val order = (0 until nf + ns + nd).sortedBy { depth[it] }

    val path = Path()
    val edgeStroke = Stroke(0.6.dp.toPx())
    for (index in order) {
        when {
            index < nf -> {
                val f = faces[index]
                val xy = faceXY[index]!!
                val light = 0.38f + 0.62f * abs(f.nx * lx + f.ny * ly + f.nz * lz)
                val base = Color(f.color)
                val color = Color(
                    (base.red * light).coerceIn(0f, 1f),
                    (base.green * light).coerceIn(0f, 1f),
                    (base.blue * light).coerceIn(0f, 1f),
                    f.alpha
                )
                path.reset()
                path.moveTo(xy[0], xy[1])
                var k = 2
                while (k < xy.size) {
                    path.lineTo(xy[k], xy[k + 1])
                    k += 2
                }
                path.close()
                drawPath(path, color)
                if (f.edges) drawPath(path, Color(0f, 0f, 0f, 0.22f), style = edgeStroke)
            }
            index < nf + ns -> {
                val i = index - nf
                val s = segs[i]
                val a = Offset(segXY[4 * i], segXY[4 * i + 1])
                val b = Offset(segXY[4 * i + 2], segXY[4 * i + 3])
                val color = Color(s.color)
                drawLine(
                    color, a, b, s.width.dp.toPx(), StrokeCap.Round,
                    when (s.dash) {
                        Dash.SOLID -> null
                        Dash.DASHED -> PathEffect.dashPathEffect(floatArrayOf(8 * density, 6 * density))
                        Dash.DOTTED -> PathEffect.dashPathEffect(floatArrayOf(1.5f * density, 5 * density))
                    }
                )
                if (s.arrow) arrowHead(b, atan2(b.y - a.y, b.x - a.x), color, max(9.dp.toPx(), s.width.dp.toPx() * 3.6f))
            }
            else -> {
                val i = index - nf - ns
                val c = Offset(dotXY[2 * i], dotXY[2 * i + 1])
                drawCircle(Color(dots[i].color), 4.5.dp.toPx(), c)
                drawCircle(Color(0xFF202124), 4.5.dp.toPx(), c, style = Stroke(1.dp.toPx()))
            }
        }
    }

    // Labels last, so they are never hidden.
    val at = FloatArray(2)
    for (label in mesh.labels) {
        cam.project(label.x, label.y, label.z, w, h, at, 0)
        val style = when (label.kind) {
            LabelKind.AXIS -> TextStyle(color = TEXT, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, shadow = HALO)
            LabelKind.TICK -> TextStyle(color = MUTED, fontSize = 10.sp, shadow = HALO)
            LabelKind.ITEM -> TextStyle(color = Color(label.color), fontSize = 13.sp, fontWeight = FontWeight.Medium, shadow = HALO)
        }
        val layout = measurer.measure(MathText.plain(label.text), style)
        val tw = layout.size.width.toFloat()
        val th = layout.size.height.toFloat()
        val (x, y) = when (label.kind) {
            LabelKind.ITEM -> (at[0] + 7.dp.toPx()) to (at[1] - th - 3.dp.toPx())
            else -> (at[0] - tw / 2) to (at[1] - th / 2)
        }
        drawText(layout, topLeft = Offset(x.coerceIn(2f, max(2f, w - tw - 2f)), y.coerceIn(2f, max(2f, h - th - 2f))))
    }
}

private val HALO = Shadow(Color.Black.copy(alpha = 0.85f), Offset.Zero, 6f)

private fun DrawScope.arrowHead(tip: Offset, angle: Float, color: Color, length: Float) {
    val half = length * 0.42f
    val back = Offset(tip.x - length * cos(angle), tip.y - length * sin(angle))
    val nx = -sin(angle) * half
    val ny = cos(angle) * half
    val head = Path().apply {
        moveTo(tip.x, tip.y)
        lineTo(back.x + nx, back.y + ny)
        lineTo(back.x - nx, back.y - ny)
        close()
    }
    drawPath(head, color)
    drawPath(head, color, style = Stroke(1f, join = StrokeJoin.Round))
}
