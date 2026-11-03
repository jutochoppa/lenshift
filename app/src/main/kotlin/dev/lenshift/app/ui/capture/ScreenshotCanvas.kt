package dev.lenshift.app.ui.capture

import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.SweepGradient
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.magnifier
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import dev.lenshift.app.selection.IntRect
import dev.lenshift.app.selection.PointF2
import dev.lenshift.app.selection.SelectionMode
import dev.lenshift.app.ui.theme.AssistantCaptureColors
import kotlin.math.max
import kotlin.math.min

/** Everything the canvas reports back. Coordinates are always source-bitmap pixels. */
interface CanvasGestures {
    fun onStrokeUpdate(points: List<PointF2>)
    fun onStrokeEnd(points: List<PointF2>)
    fun onBoxUpdate(rect: Rect?)
    fun onBoxEnd(rect: Rect?)
    fun onTap(point: PointF2)
    fun onLongPress(point: PointF2)
    /** The finger moved after a long-press without lifting: extend the text selection. */
    fun onLongPressDrag(point: PointF2)
    fun onLongPressEnd()
    fun onResize(rect: IntRect)
    fun onResizeEnd(rect: IntRect)
    /** [isStart] says which text handle is being dragged. */
    fun onHandleDrag(isStart: Boolean, point: PointF2)
    fun onHandleEnd()
}

/** Text-selection handle anchors: bottom-left of the first word, bottom-right of the last. */
data class TextHandles(val start: PointF2, val end: PointF2)

/** Maps source-bitmap points into the canvas's own coordinates, for overlays. */
class CanvasMapper(val fit: Rect, private val bitmapWidth: Int, private val bitmapHeight: Int) {
    fun toCanvas(p: PointF2): Offset = sourceToCanvas(p, fit, bitmapWidth, bitmapHeight)
    fun toCanvas(r: IntRect): Rect = Rect(
        toCanvas(PointF2(r.left.toFloat(), r.top.toFloat())),
        toCanvas(PointF2(r.right.toFloat(), r.bottom.toFloat()))
    )
}

private const val OUTCOME_TAP = 1
private const val OUTCOME_DRAG = 2

/**
 * The captured screen plus its selection gestures and selection rendering.
 *
 * Coordinate contract: every value reported through [CanvasGestures] is in source-bitmap
 * pixels, never composable pixels, so crops, OCR boxes and uploads stay aligned however the
 * preview is scaled.
 *
 * One gesture handler arbitrates, in order: a text-selection handle under the finger
 * (drag it, with a magnifier); a corner of the region selection (resize it live); otherwise
 * tap, long-press (optionally dragging to extend a text selection), or a drag that draws a
 * circle/scribble — or a box in [SelectionMode.RECTANGLE].
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ScreenshotCanvas(
    bitmap: Bitmap,
    mode: SelectionMode,
    stroke: List<PointF2>,
    boxDraft: Rect?,
    selection: IntRect?,
    textHighlights: List<IntRect>,
    textHandles: TextHandles?,
    cornerRadius: Dp,
    glowPhase: () -> Float,
    gestures: CanvasGestures,
    modifier: Modifier = Modifier,
    overlay: @Composable BoxScope.(CanvasMapper) -> Unit = {}
) {
    var canvasSize by remember { mutableStateOf(Size.Zero) }
    var magnifierAt by remember { mutableStateOf(Offset.Unspecified) }
    val image = remember(bitmap) { bitmap.asImageBitmap() }
    val density = LocalDensity.current

    val fit = remember(canvasSize, bitmap.width, bitmap.height) {
        computeFitRect(canvasSize.width, canvasSize.height, bitmap.width, bitmap.height)
    }
    val mapper = remember(fit, bitmap) { CanvasMapper(fit, bitmap.width, bitmap.height) }

    // The gesture block outlives recompositions; read the latest inputs through state.
    val callbacks by rememberUpdatedState(gestures)
    val currentSelection by rememberUpdatedState(selection)
    val currentHandles by rememberUpdatedState(textHandles)
    val handleRadius = with(density) { 11.dp.toPx() }

    fun toSource(offset: Offset): PointF2 = canvasToSource(offset, fit, bitmap.width, bitmap.height)
    fun inside(offset: Offset): Boolean = fit.width > 0f && fit.contains(offset)

    Box(
        modifier = modifier
            .fillMaxSize()
            .onSizeChanged { canvasSize = Size(it.width.toFloat(), it.height.toFloat()) }
            .semantics { contentDescription = "Captured screen. Circle, tap, or long-press text." }
            .magnifier(
                sourceCenter = { magnifierAt },
                magnifierCenter = {
                    if (magnifierAt.isSpecified) magnifierAt - Offset(0f, with(density) { 72.dp.toPx() })
                    else Offset.Unspecified
                },
                zoom = 1.6f,
                size = DpSize(120.dp, 52.dp),
                cornerRadius = 26.dp,
                elevation = 6.dp
            )
            .pointerInput(mode, fit, bitmap) {
                val handleHit = 30.dp.toPx()
                val cornerHit = 44.dp.toPx()
                awaitEachGesture {
                    // Touches the toolbar or chip already handled must not also select underneath.
                    val down = awaitFirstDown(requireUnconsumed = true)
                    val start = down.position

                    // 1. Text-selection handles.
                    currentHandles?.let { handles ->
                        val s = startHandleCenter(sourceToCanvas(handles.start, fit, bitmap.width, bitmap.height), handleRadius)
                        val e = endHandleCenter(sourceToCanvas(handles.end, fit, bitmap.width, bitmap.height), handleRadius)
                        val ds = (start - s).getDistance()
                        val de = (start - e).getDistance()
                        if (min(ds, de) <= handleHit) {
                            val isStart = ds < de
                            down.consume()
                            // The finger sits below the text; aim at the line just above it.
                            val lift = Offset(0f, handleRadius * 2.4f)
                            magnifierAt = start - lift
                            drag(down.id) { change ->
                                change.consume()
                                magnifierAt = change.position - lift
                                callbacks.onHandleDrag(isStart, toSource(change.position - lift))
                            }
                            magnifierAt = Offset.Unspecified
                            callbacks.onHandleEnd()
                            return@awaitEachGesture
                        }
                    }

                    // 2. Corners of the region selection.
                    val region = currentSelection
                    if (region != null && !region.isEmpty) {
                        val frame = Rect(
                            sourceToCanvas(PointF2(region.left.toFloat(), region.top.toFloat()), fit, bitmap.width, bitmap.height),
                            sourceToCanvas(PointF2(region.right.toFloat(), region.bottom.toFloat()), fit, bitmap.width, bitmap.height)
                        ).inflate(6.dp.toPx())
                        val corners = listOf(frame.topLeft, frame.topRight, frame.bottomRight, frame.bottomLeft)
                        val nearest = corners.indices.minBy { (corners[it] - start).getDistance() }
                        if ((corners[nearest] - start).getDistance() <= cornerHit) {
                            down.consume()
                            val opposite = when (nearest) {
                                0 -> PointF2(region.right.toFloat(), region.bottom.toFloat())
                                1 -> PointF2(region.left.toFloat(), region.bottom.toFloat())
                                2 -> PointF2(region.left.toFloat(), region.top.toFloat())
                                else -> PointF2(region.right.toFloat(), region.top.toFloat())
                            }
                            val minSize = (min(bitmap.width, bitmap.height) * 0.04f).toInt().coerceAtLeast(24)
                            var current = region
                            drag(down.id) { change ->
                                change.consume()
                                val p = toSource(change.position)
                                current = resizedRect(opposite, p, minSize, bitmap.width, bitmap.height)
                                callbacks.onResize(current)
                            }
                            callbacks.onResizeEnd(current)
                            return@awaitEachGesture
                        }
                    }

                    // 3. Tap, long-press, or drag.
                    val outcome = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                        var result = OUTCOME_TAP
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id }
                            if (change == null || !change.pressed) {
                                result = OUTCOME_TAP
                                break
                            }
                            if ((change.position - start).getDistance() > viewConfiguration.touchSlop) {
                                result = OUTCOME_DRAG
                                break
                            }
                        }
                        result
                    }

                    when (outcome) {
                        null -> {
                            if (!inside(start)) return@awaitEachGesture
                            callbacks.onLongPress(toSource(start))
                            drag(down.id) { change ->
                                change.consume()
                                magnifierAt = change.position
                                callbacks.onLongPressDrag(toSource(change.position))
                            }
                            magnifierAt = Offset.Unspecified
                            callbacks.onLongPressEnd()
                        }

                        OUTCOME_TAP -> if (inside(start)) callbacks.onTap(toSource(start))

                        else -> when (mode) {
                            SelectionMode.RECTANGLE -> {
                                val origin = toSource(start)
                                var rect: Rect? = Rect(origin.x, origin.y, origin.x, origin.y)
                                callbacks.onBoxUpdate(rect)
                                drag(down.id) { change ->
                                    change.consume()
                                    val p = toSource(change.position)
                                    rect = Rect(
                                        min(origin.x, p.x), min(origin.y, p.y),
                                        max(origin.x, p.x), max(origin.y, p.y)
                                    )
                                    callbacks.onBoxUpdate(rect)
                                }
                                callbacks.onBoxEnd(rect)
                            }

                            SelectionMode.FULL -> Unit

                            else -> {
                                val points = mutableListOf(toSource(start))
                                callbacks.onStrokeUpdate(points.toList())
                                drag(down.id) { change ->
                                    change.consume()
                                    points.add(toSource(change.position))
                                    callbacks.onStrokeUpdate(points.toList())
                                }
                                callbacks.onStrokeEnd(points.toList())
                            }
                        }
                    }
                }
            }
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            if (fit.width <= 0f) return@Canvas
            val radiusPx = cornerRadius.toPx()

            // 1. The screenshot, rounded when shown as a preview card.
            val imageClip = Path().apply { addRoundRect(RoundRect(fit, CornerRadius(radiusPx))) }
            clipPath(imageClip) {
                drawImage(
                    image = image,
                    dstOffset = IntOffset(fit.left.toInt(), fit.top.toInt()),
                    dstSize = IntSize(fit.width.toInt(), fit.height.toInt()),
                    filterQuality = FilterQuality.Medium
                )
            }

            // 2. Region selection (or box draft): dim the rest, glow, brackets.
            val committed = selection?.takeIf { !it.isEmpty }?.let { mapper.toCanvas(it) }
            val draft = boxDraft?.takeIf { it.width > 1f && it.height > 1f }?.let {
                Rect(mapper.toCanvas(PointF2(it.left, it.top)), mapper.toCanvas(PointF2(it.right, it.bottom)))
            }
            val framed = draft ?: committed
            if (framed != null) {
                val frameRadius = 18.dp.toPx()
                val hole = Path().apply { addRoundRect(RoundRect(framed, CornerRadius(frameRadius))) }
                val dim = Path().apply {
                    addPath(imageClip)
                    addPath(hole)
                    fillType = PathFillType.EvenOdd
                }
                drawPath(dim, Color.Black.copy(alpha = 0.55f))
                if (draft == null) drawSelectionGlow(framed, frameRadius, hole, glowPhase())
                drawCornerBrackets(framed.inflate(6.dp.toPx()), frameRadius + 6.dp.toPx())
            }

            // 3. Text selection: per-line highlights and two teardrop handles.
            textHighlights.forEach { line ->
                val r = mapper.toCanvas(line).inflate(2.dp.toPx())
                drawRoundRect(
                    color = AssistantCaptureColors.TextSelection,
                    topLeft = r.topLeft,
                    size = r.size,
                    cornerRadius = CornerRadius(4.dp.toPx())
                )
            }
            textHandles?.let { h ->
                drawHandle(mapper.toCanvas(h.start), handleRadius, isStart = true)
                drawHandle(mapper.toCanvas(h.end), handleRadius, isStart = false)
            }

            // 4. Live freehand stroke.
            if (stroke.size >= 2) drawGlowingStroke(stroke.map { mapper.toCanvas(it) })
        }

        overlay(mapper)
    }
}

/** A rectangle spanning [fixed] and [moving], clamped to the image and at least [minSize]. */
internal fun resizedRect(fixed: PointF2, moving: PointF2, minSize: Int, width: Int, height: Int): IntRect {
    var left = min(fixed.x, moving.x)
    var right = max(fixed.x, moving.x)
    var top = min(fixed.y, moving.y)
    var bottom = max(fixed.y, moving.y)
    if (right - left < minSize) {
        if (moving.x < fixed.x) left = fixed.x - minSize else right = fixed.x + minSize
    }
    if (bottom - top < minSize) {
        if (moving.y < fixed.y) top = fixed.y - minSize else bottom = fixed.y + minSize
    }
    return IntRect(
        left.toInt().coerceIn(0, width),
        top.toInt().coerceIn(0, height),
        right.toInt().coerceIn(0, width),
        bottom.toInt().coerceIn(0, height)
    )
}

private fun startHandleCenter(anchor: Offset, r: Float) = Offset(anchor.x - r, anchor.y + r)
private fun endHandleCenter(anchor: Offset, r: Float) = Offset(anchor.x + r, anchor.y + r)

/** Android-style teardrop: a circle whose square corner points at the text. */
private fun DrawScope.drawHandle(anchor: Offset, r: Float, isStart: Boolean) {
    val color = AssistantCaptureColors.TextHandle
    val center = if (isStart) startHandleCenter(anchor, r) else endHandleCenter(anchor, r)
    drawCircle(color, radius = r, center = center)
    drawRect(
        color = color,
        topLeft = if (isStart) Offset(anchor.x - r, anchor.y) else Offset(anchor.x, anchor.y),
        size = Size(r, r)
    )
}

/** A white stroke with a soft mint halo, drawn while the finger is down. */
private fun DrawScope.drawGlowingStroke(points: List<Offset>) {
    val path = Path().apply {
        moveTo(points.first().x, points.first().y)
        for (i in 1 until points.size) {
            val a = points[i - 1]
            val b = points[i]
            quadraticTo(a.x, a.y, (a.x + b.x) / 2f, (a.y + b.y) / 2f)
        }
        lineTo(points.last().x, points.last().y)
    }
    drawIntoCanvas { canvas ->
        val halo = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            style = android.graphics.Paint.Style.STROKE
            strokeWidth = 14.dp.toPx()
            strokeCap = android.graphics.Paint.Cap.ROUND
            strokeJoin = android.graphics.Paint.Join.ROUND
            color = AssistantCaptureColors.Accent.copy(alpha = 0.6f).toArgb()
            maskFilter = BlurMaskFilter(10.dp.toPx(), BlurMaskFilter.Blur.NORMAL)
        }
        canvas.nativeCanvas.drawPath(path.asAndroidPath(), halo)
    }
    drawPath(
        path,
        Color.White,
        style = Stroke(width = 5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
    )
}

/** Multicolour blurred ring hugging the selection, clipped to stay outside it. */
private fun DrawScope.drawSelectionGlow(rect: Rect, radius: Float, hole: Path, phase: Float) {
    val colors = AssistantCaptureColors.Glow.map { it.toArgb() }.toIntArray()
    drawIntoCanvas { canvas ->
        val shader = SweepGradient(rect.center.x, rect.center.y, colors, null).apply {
            setLocalMatrix(
                android.graphics.Matrix().apply { setRotate(phase * 360f, rect.center.x, rect.center.y) }
            )
        }
        val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            style = android.graphics.Paint.Style.STROKE
            strokeWidth = 10.dp.toPx()
            this.shader = shader
            alpha = 220
            maskFilter = BlurMaskFilter(14.dp.toPx(), BlurMaskFilter.Blur.NORMAL)
        }
        val r = rect.inflate(3.dp.toPx())
        val native = canvas.nativeCanvas
        native.save()
        native.clipOutPath(hole.asAndroidPath())
        native.drawRoundRect(r.left, r.top, r.right, r.bottom, radius, radius, paint)
        native.restore()
    }
}

/** Four rounded L-shaped corner marks around [rect]. */
private fun DrawScope.drawCornerBrackets(rect: Rect, radius: Float) {
    val arm = minOf(30.dp.toPx(), rect.width / 3f, rect.height / 3f)
    val r = minOf(radius, arm)
    val path = Path().apply {
        moveTo(rect.left, rect.top + arm)
        lineTo(rect.left, rect.top + r)
        quadraticTo(rect.left, rect.top, rect.left + r, rect.top)
        lineTo(rect.left + arm, rect.top)

        moveTo(rect.right - arm, rect.top)
        lineTo(rect.right - r, rect.top)
        quadraticTo(rect.right, rect.top, rect.right, rect.top + r)
        lineTo(rect.right, rect.top + arm)

        moveTo(rect.right, rect.bottom - arm)
        lineTo(rect.right, rect.bottom - r)
        quadraticTo(rect.right, rect.bottom, rect.right - r, rect.bottom)
        lineTo(rect.right - arm, rect.bottom)

        moveTo(rect.left + arm, rect.bottom)
        lineTo(rect.left + r, rect.bottom)
        quadraticTo(rect.left, rect.bottom, rect.left, rect.bottom - r)
        lineTo(rect.left, rect.bottom - arm)
    }
    drawPath(
        path,
        AssistantCaptureColors.Bracket,
        style = Stroke(width = 4.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
    )
}

/**
 * Computes the rectangle a `ContentScale.Fit` image occupies inside its container.
 */
internal fun computeFitRect(
    containerWidth: Float,
    containerHeight: Float,
    bitmapWidth: Int,
    bitmapHeight: Int
): Rect {
    if (containerWidth <= 0f || containerHeight <= 0f ||
        bitmapWidth <= 0 || bitmapHeight <= 0
    ) {
        return Rect.Zero
    }

    val scale = minOf(
        containerWidth / bitmapWidth,
        containerHeight / bitmapHeight
    )
    val width = bitmapWidth * scale
    val height = bitmapHeight * scale
    val left = (containerWidth - width) / 2f
    val top = (containerHeight - height) / 2f
    return Rect(left, top, left + width, top + height)
}

internal fun canvasToSource(
    offset: Offset,
    fit: Rect,
    bitmapWidth: Int,
    bitmapHeight: Int
): PointF2 {
    if (fit.width <= 0f || fit.height <= 0f) return PointF2(0f, 0f)
    return PointF2(
        ((offset.x - fit.left) / fit.width * bitmapWidth)
            .coerceIn(0f, bitmapWidth.toFloat()),
        ((offset.y - fit.top) / fit.height * bitmapHeight)
            .coerceIn(0f, bitmapHeight.toFloat())
    )
}

internal fun sourceToCanvas(
    point: PointF2,
    fit: Rect,
    bitmapWidth: Int,
    bitmapHeight: Int
): Offset {
    if (bitmapWidth <= 0 || bitmapHeight <= 0) return Offset.Zero
    return Offset(
        fit.left + point.x / bitmapWidth * fit.width,
        fit.top + point.y / bitmapHeight * fit.height
    )
}
