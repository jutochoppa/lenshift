package dev.lenshift.app.ui.visual

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.lenshift.app.visual.AlongGraph
import dev.lenshift.app.visual.Anchor
import dev.lenshift.app.visual.AroundCircle
import dev.lenshift.app.visual.Dash
import dev.lenshift.app.visual.FreeDrag
import dev.lenshift.app.visual.GAngle
import dev.lenshift.app.visual.GMask
import dev.lenshift.app.visual.GPath
import dev.lenshift.app.visual.GPie
import dev.lenshift.app.visual.Graph2D
import dev.lenshift.app.visual.MathText
import dev.lenshift.app.visual.Plot2D
import dev.lenshift.app.visual.Resolve2D
import dev.lenshift.app.visual.Scene2D
import dev.lenshift.app.visual.View2
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * A 2D graph: grid, axes and ticks, then everything the spec draws. Inside the canvas,
 * dragging a draggable point moves it (along its curve or circle for gliders), dragging
 * elsewhere pans, pinching zooms, and a double tap resets view and points.
 */
@Composable
fun Graph2DCanvas(graph: Graph2D, state: VisualState, modifier: Modifier = Modifier) {
    val measurer = rememberTextMeasurer(cacheSize = 96)
    var size by remember { mutableStateOf(IntSize.Zero) }
    val initial = remember(graph) { graph.params.map { it.value }.toDoubleArray() }
    val base = remember(graph) {
        val probe = Resolve2D.resolve(graph, initial, emptyMap(), View2(-10.0, 10.0, -10.0, 10.0), 800f, 600f)
        Plot2D.autoView(graph, probe)
    }
    val equal = remember(graph) { Plot2D.wantsEqualAspect(graph) }
    val w = size.width.toFloat()
    val h = size.height.toFloat()
    val view = (state.view ?: base).let { v -> if (equal && w > 0 && h > 0 && state.view == null) v.equalised(w, h) else v }
    val sliders = state.sliders.toList()
    val drags = state.drags.toMap()
    val scene = remember(graph, sliders, drags, view, size) {
        Resolve2D.resolve(graph, sliders.toDoubleArray(), drags, view, max(w, 1f), max(h, 1f))
    }
    SideEffect {
        state.legend = scene.legend
        state.runtimeNotes = scene.notes
    }
    val currentScene by rememberUpdatedState(scene)
    val currentView by rememberUpdatedState(view)

    Canvas(
        modifier
            .onSizeChanged { size = it }
            .pointerInput(graph) {
                val touch = 28.dp.toPx()
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val cw = this.size.width.toFloat()
                    val ch = this.size.height.toFloat()
                    val v0 = state.view ?: currentView
                    val hit = currentScene.dots.asSequence()
                        .filter { it.drag != null }
                        .map { it to hypot(sx(it.x, v0, cw) - down.position.x, sy(it.y, v0, ch) - down.position.y) }
                        .filter { it.second < touch }
                        .minByOrNull { it.second }?.first
                    if (hit != null) {
                        // Move the point; it follows its curve or circle when it has one.
                        down.consume()
                        state.interacting = true
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (!change.pressed) break
                            val v = state.view ?: currentView
                            val gx = v.xMin + change.position.x / cw * v.width
                            val gy = v.yMin + (ch - change.position.y) / ch * v.height
                            val drag = hit.drag!!
                            state.drags[drag.key] = when (drag) {
                                is FreeDrag -> doubleArrayOf(gx, gy)
                                is AlongGraph -> doubleArrayOf(gx)
                                is AroundCircle -> doubleArrayOf(Math.toDegrees(atan2(gy - drag.cy, gx - drag.cx)))
                            }
                            change.consume()
                        }
                        state.interacting = false
                    } else {
                        var moving = false
                        var travelled = Offset.Zero
                        do {
                            val event = awaitPointerEvent()
                            val zoom = event.calculateZoom()
                            val pan = event.calculatePan()
                            if (!moving) {
                                travelled += pan
                                if (travelled.getDistance() > viewConfiguration.touchSlop || abs(zoom - 1f) > 0.02f) moving = true
                            }
                            if (moving) {
                                val v = state.view ?: currentView
                                var next = v.panned(-pan.x / cw * v.width, pan.y / ch * v.height)
                                val centroid = event.calculateCentroid(useCurrent = true)
                                if (zoom != 1f && centroid.isSpecified) {
                                    val cx = next.xMin + centroid.x / cw * next.width
                                    val cy = next.yMin + (ch - centroid.y) / ch * next.height
                                    next = next.zoomed(zoom.toDouble(), cx, cy)
                                }
                                state.view = next
                                event.changes.forEach { if (it.positionChanged()) it.consume() }
                            }
                        } while (event.changes.any { it.pressed })
                    }
                }
            }
            .pointerInput(graph) { detectTapGestures(onDoubleTap = { state.resetView() }) }
    ) {
        if (size.width <= 0 || size.height <= 0) return@Canvas
        clipRect {
            drawGraph(graph, scene, view, measurer)
        }
    }
}

// ============================================================================= drawing

private val GRID = Color(0x1AE8EAED)
private val AXIS = Color(0xB3BDC1C6)
private val TICK_TEXT = Color(0xFF9AA0A6)
private val TEXT = Color(0xFFE8EAED)

private fun sx(x: Double, v: View2, w: Float) = ((x - v.xMin) / v.width * w).toFloat().coerceIn(-1e5f, 1e5f)
private fun sy(y: Double, v: View2, h: Float) = (h - (y - v.yMin) / v.height * h).toFloat().coerceIn(-1e5f, 1e5f)

private fun DrawScope.drawGraph(graph: Graph2D, scene: Scene2D, view: View2, measurer: TextMeasurer) {
    val w = size.width
    val h = size.height
    val pieOnly = scene.pies.isNotEmpty() && scene.paths.isEmpty() && scene.dots.isEmpty() && scene.masks.isEmpty()
    if (!pieOnly) axes(graph, view, measurer)

    scene.masks.forEach { mask(it, view) }
    // Fills first, so outlines and curves stay on top.
    scene.paths.filter { it.fill != null }.forEach { path(it, view, fillOnly = true) }
    scene.paths.forEach { path(it, view, fillOnly = false) }
    scene.angles.forEach { angle(it, view, measurer) }
    scene.pies.forEach { pie(it, measurer) }

    for (dot in scene.dots) {
        val c = Offset(sx(dot.x, view, w), sy(dot.y, view, h))
        val color = Color(dot.color)
        if (dot.drag != null) {
            drawCircle(color.copy(alpha = 0.28f), 11.dp.toPx(), c)
            drawCircle(color, 6.dp.toPx(), c)
            drawCircle(Color.White, 6.dp.toPx(), c, style = Stroke(1.5.dp.toPx()))
        } else {
            drawCircle(color, 4.5.dp.toPx(), c)
            drawCircle(Color(0xFF202124), 4.5.dp.toPx(), c, style = Stroke(1.dp.toPx()))
        }
        dot.label?.let { text(measurer, MathText.plain(it), c.x + 8.dp.toPx(), c.y - 6.dp.toPx(), color, Anchor.RIGHT, 12f, lift = true) }
    }
    for (t in scene.texts) {
        text(measurer, MathText.plain(t.text), sx(t.x, view, w), sy(t.y, view, h), Color(t.color), t.anchor, 12f, boxed = t.boxed)
    }
}

/** Grid, axes with arrows, tick labels and axis names. */
private fun DrawScope.axes(graph: Graph2D, view: View2, measurer: TextMeasurer) {
    val w = size.width
    val h = size.height
    val xStep = if (graph.piX) piStep(view.width) else Plot2D.niceStep(view.width, (w / 72.dp.toPx()).roundToInt().coerceIn(3, 14))
    val yStep = when {
        graph.piY -> piStep(view.height)
        Plot2D.wantsEqualAspect(graph) && !graph.piX -> xStep
        else -> Plot2D.niceStep(view.height, (h / 48.dp.toPx()).roundToInt().coerceIn(3, 12))
    }
    val xTicks = Plot2D.ticks(view.xMin, view.xMax, xStep)
    val yTicks = Plot2D.ticks(view.yMin, view.yMax, yStep)
    if (graph.grid) {
        xTicks.forEach { x -> drawLine(GRID, Offset(sx(x, view, w), 0f), Offset(sx(x, view, w), h), 1f) }
        yTicks.forEach { y -> drawLine(GRID, Offset(0f, sy(y, view, h)), Offset(w, sy(y, view, h)), 1f) }
    }
    if (!graph.axes) return
    val stroke = 1.3.dp.toPx()
    val xAxisY = if (0.0 in view.yMin..view.yMax) sy(0.0, view, h) else null
    val yAxisX = if (0.0 in view.xMin..view.xMax) sx(0.0, view, w) else null
    xAxisY?.let { y ->
        drawLine(AXIS, Offset(0f, y), Offset(w, y), stroke)
        arrowHead(Offset(w - 1, y), 0f, AXIS, 9.dp.toPx())
    }
    yAxisX?.let { x ->
        drawLine(AXIS, Offset(x, h), Offset(x, 0f), stroke)
        arrowHead(Offset(x, 1f), (-PI / 2).toFloat(), AXIS, 9.dp.toPx())
    }
    // Tick labels sit beside the axis, or along the edge when the axis is off screen.
    val labelY = (xAxisY ?: (h - 14.dp.toPx())).coerceIn(0f, h - 14.dp.toPx())
    val labelX = (yAxisX ?: 4.dp.toPx()).coerceIn(4.dp.toPx(), w)
    xTicks.forEach { x ->
        if (x == 0.0 && yAxisX != null) return@forEach
        val px = sx(x, view, w)
        if (px < 10.dp.toPx() || px > w - 10.dp.toPx()) return@forEach
        drawLine(AXIS, Offset(px, labelY - 3.dp.toPx()), Offset(px, labelY + 3.dp.toPx()), stroke)
        text(measurer, if (graph.piX) piLabel(x) else Plot2D.formatTick(x, xStep), px, labelY + 4.dp.toPx(), TICK_TEXT, Anchor.BELOW, 11f)
    }
    yTicks.forEach { y ->
        if (y == 0.0 && xAxisY != null) return@forEach
        val py = sy(y, view, h)
        if (py < 10.dp.toPx() || py > h - 10.dp.toPx()) return@forEach
        drawLine(AXIS, Offset(labelX - 3.dp.toPx(), py), Offset(labelX + 3.dp.toPx(), py), stroke)
        val label = if (graph.piY) piLabel(y) else Plot2D.formatTick(y, yStep)
        val layout = measurer.measure(label, TextStyle(color = TICK_TEXT, fontSize = 11.sp))
        val left = labelX - 6.dp.toPx() - layout.size.width
        val x = if (left < 2.dp.toPx()) labelX + 6.dp.toPx() else left
        drawText(layout, topLeft = Offset(x, py - layout.size.height / 2f))
    }
    if (xAxisY != null && yAxisX != null) {
        text(measurer, "0", yAxisX - 5.dp.toPx(), xAxisY + 4.dp.toPx(), TICK_TEXT, Anchor.BELOW, 11f, alignEnd = true)
    }
    graph.xLabel?.let { text(measurer, MathText.plain(it), w - 6.dp.toPx(), (xAxisY ?: h) - 8.dp.toPx(), TEXT, Anchor.ABOVE, 12f, alignEnd = true) }
    graph.yLabel?.let { text(measurer, MathText.plain(it), (yAxisX ?: 0f) + 10.dp.toPx(), 4.dp.toPx(), TEXT, Anchor.RIGHT, 12f, top = true) }
}

/** A step of π/6, π/4, π/2, π, 2π… giving at most about ten ticks. */
private fun piStep(span: Double): Double =
    listOf(PI / 6, PI / 4, PI / 2, PI, 2 * PI, 4 * PI, 8 * PI).firstOrNull { span / it <= 10 } ?: (16 * PI)

/** "π/2", "π", "3π/2", "−2π" for multiples of π. */
private fun piLabel(value: Double): String {
    if (abs(value) < 1e-12) return "0"
    for (d in listOf(1, 2, 3, 4, 6)) {
        val n = value / PI * d
        if (abs(n - n.roundToInt()) < 1e-6) {
            val k = n.roundToInt()
            val sign = if (k < 0) "−" else ""
            val num = abs(k)
            val top = if (num == 1) "π" else "${num}π"
            return sign + if (d == 1) top else "$top/$d"
        }
    }
    return Plot2D.formatTick(value, 0.01)
}

private fun DrawScope.mask(mask: GMask, view: View2) {
    val bitmap = Bitmap.createBitmap(mask.nx, mask.ny, Bitmap.Config.ARGB_8888)
    val pixels = IntArray(mask.nx * mask.ny)
    for (row in 0 until mask.ny) {
        val cellRow = mask.ny - 1 - row
        for (col in 0 until mask.nx) {
            if (mask.cells[cellRow * mask.nx + col]) pixels[row * mask.nx + col] = mask.color
        }
    }
    bitmap.setPixels(pixels, 0, mask.nx, 0, 0, mask.nx, mask.ny)
    val left = sx(mask.view.xMin, view, size.width)
    val top = sy(mask.view.yMax, view, size.height)
    val right = sx(mask.view.xMax, view, size.width)
    val bottom = sy(mask.view.yMin, view, size.height)
    drawImage(
        bitmap.asImageBitmap(),
        dstOffset = IntOffset(left.roundToInt(), top.roundToInt()),
        dstSize = IntSize((right - left).roundToInt(), (bottom - top).roundToInt()),
        filterQuality = FilterQuality.Low
    )
}

private fun dashEffect(dash: Dash, density: Float): PathEffect? = when (dash) {
    Dash.SOLID -> null
    Dash.DASHED -> PathEffect.dashPathEffect(floatArrayOf(9 * density, 6 * density))
    Dash.DOTTED -> PathEffect.dashPathEffect(floatArrayOf(1.5f * density, 5 * density))
}

private fun DrawScope.path(p: GPath, view: View2, fillOnly: Boolean) {
    val w = size.width
    val h = size.height
    val n = p.xy.size / 2
    if (n < 2) return
    val path = Path()
    if (p.segments) {
        var k = 0
        while (k + 3 < p.xy.size) {
            path.moveTo(sx(p.xy[k], view, w), sy(p.xy[k + 1], view, h))
            path.lineTo(sx(p.xy[k + 2], view, w), sy(p.xy[k + 3], view, h))
            k += 4
        }
    } else {
        path.moveTo(sx(p.xy[0], view, w), sy(p.xy[1], view, h))
        for (i in 1 until n) path.lineTo(sx(p.xy[2 * i], view, w), sy(p.xy[2 * i + 1], view, h))
        if (p.closed) path.close()
    }
    if (fillOnly) {
        p.fill?.let { drawPath(path, Color(it)) }
        return
    }
    val stroke = p.stroke ?: return
    val color = Color(stroke)
    drawPath(
        path, color,
        style = Stroke(
            width = p.width.dp.toPx(),
            cap = StrokeCap.Round,
            join = StrokeJoin.Round,
            pathEffect = dashEffect(p.dash, density)
        )
    )
    if (p.arrow) {
        val size = max(8.dp.toPx(), p.width.dp.toPx() * 3.6f)
        if (p.segments) {
            var k = 0
            while (k + 3 < p.xy.size) {
                val a = Offset(sx(p.xy[k], view, w), sy(p.xy[k + 1], view, h))
                val b = Offset(sx(p.xy[k + 2], view, w), sy(p.xy[k + 3], view, h))
                arrowHead(b, atan2(b.y - a.y, b.x - a.x), color, min(size, (b - a).getDistance() * 0.6f))
                k += 4
            }
        } else {
            val a = Offset(sx(p.xy[2 * n - 4], view, w), sy(p.xy[2 * n - 3], view, h))
            val b = Offset(sx(p.xy[2 * n - 2], view, w), sy(p.xy[2 * n - 1], view, h))
            arrowHead(b, atan2(b.y - a.y, b.x - a.x), color, size)
        }
    }
}

/** A filled arrowhead with its tip at [tip], pointing along [angle] (screen radians). */
private fun DrawScope.arrowHead(tip: Offset, angle: Float, color: Color, length: Float) {
    if (length <= 0f) return
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
}

private fun DrawScope.angle(a: GAngle, view: View2, measurer: TextMeasurer) {
    val w = size.width
    val h = size.height
    val v = Offset(sx(a.vertex[0], view, w), sy(a.vertex[1], view, h))
    val p1 = Offset(sx(a.from[0], view, w), sy(a.from[1], view, h))
    val p2 = Offset(sx(a.to[0], view, w), sy(a.to[1], view, h))
    val a1 = atan2(p1.y - v.y, p1.x - v.x)
    val a2 = atan2(p2.y - v.y, p2.x - v.x)
    var sweep = a2 - a1
    while (sweep <= -PI) sweep += (2 * PI).toFloat()
    while (sweep > PI) sweep -= (2 * PI).toFloat()
    val shortest = min((p1 - v).getDistance(), (p2 - v).getDistance())
    val r = min(26.dp.toPx(), shortest * 0.45f).coerceAtLeast(10.dp.toPx())
    val color = Color(a.color)
    if (a.right) {
        val s = r * 0.6f
        val u1 = (p1 - v) / (p1 - v).getDistance()
        val u2 = (p2 - v) / (p2 - v).getDistance()
        val square = Path().apply {
            moveTo(v.x + u1.x * s, v.y + u1.y * s)
            lineTo(v.x + (u1.x + u2.x) * s, v.y + (u1.y + u2.y) * s)
            lineTo(v.x + u2.x * s, v.y + u2.y * s)
        }
        drawPath(square, color, style = Stroke(1.6.dp.toPx()))
    } else {
        val start = Math.toDegrees(a1.toDouble()).toFloat()
        val sweepDeg = Math.toDegrees(sweep.toDouble()).toFloat()
        drawArc(color.copy(alpha = 0.25f), start, sweepDeg, true, Offset(v.x - r, v.y - r), Size(2 * r, 2 * r))
        drawArc(color, start, sweepDeg, false, Offset(v.x - r, v.y - r), Size(2 * r, 2 * r), style = Stroke(1.6.dp.toPx()))
    }
    val mid = a1 + sweep / 2
    val lr = r + 14.dp.toPx()
    text(measurer, MathText.plain(a.label), v.x + lr * cos(mid), v.y + lr * sin(mid), color, Anchor.CENTER, 12f)
}

private fun DrawScope.pie(p: GPie, measurer: TextMeasurer) {
    val total = p.values.sum()
    val r = min(size.width, size.height) * 0.36f
    val c = Offset(size.width / 2, size.height / 2)
    var start = -90f
    p.values.forEachIndexed { i, v ->
        val sweep = (v / total * 360).toFloat()
        drawArc(Color(p.colors[i]), start, sweep, true, Offset(c.x - r, c.y - r), Size(2 * r, 2 * r))
        drawArc(Color(0xFF202124), start, sweep, true, Offset(c.x - r, c.y - r), Size(2 * r, 2 * r), style = Stroke(1.5.dp.toPx()))
        val mid = Math.toRadians((start + sweep / 2).toDouble())
        val share = "${(v / total * 100).let { if (it >= 10) it.roundToInt().toString() else "%.1f".format(java.util.Locale.ROOT, it) }}%"
        val label = listOfNotNull(p.labels.getOrNull(i)?.let { MathText.plain(it) }, share).joinToString(" ")
        text(measurer, label, c.x + r * 1.22f * cos(mid).toFloat(), c.y + r * 1.22f * sin(mid).toFloat(), TEXT, Anchor.CENTER, 12f)
        start += sweep
    }
}

/**
 * Text with a dark halo so it reads over curves and fills. [lift] keeps a point's label
 * above the dot's centre line; [alignEnd] puts the text's end at x.
 */
private fun DrawScope.text(
    measurer: TextMeasurer,
    text: String,
    x: Float,
    y: Float,
    color: Color,
    anchor: Anchor,
    sizeSp: Float,
    lift: Boolean = false,
    alignEnd: Boolean = false,
    top: Boolean = false,
    boxed: Boolean = false
) {
    if (text.isBlank()) return
    val layout = measurer.measure(
        text,
        TextStyle(color = color, fontSize = sizeSp.sp, shadow = Shadow(Color.Black.copy(alpha = 0.85f), Offset.Zero, 6f))
    )
    val tw = layout.size.width.toFloat()
    val th = layout.size.height.toFloat()
    var left = when {
        alignEnd -> x - tw
        anchor == Anchor.RIGHT -> x
        else -> x - tw / 2
    }
    var topY = when {
        top -> y
        lift -> y - th
        anchor == Anchor.ABOVE -> y - th - 2.dp.toPx()
        anchor == Anchor.BELOW -> y
        else -> y - th / 2
    }
    left = left.coerceIn(2f, max(2f, size.width - tw - 2f))
    topY = topY.coerceIn(2f, max(2f, size.height - th - 2f))
    if (boxed) {
        val pad = 5.dp.toPx()
        drawRoundRect(
            Color(0xE6202124),
            topLeft = Offset(left - pad, topY - pad / 2),
            size = Size(tw + 2 * pad, th + pad),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(th)
        )
    }
    drawText(layout, topLeft = Offset(left, topY))
}
