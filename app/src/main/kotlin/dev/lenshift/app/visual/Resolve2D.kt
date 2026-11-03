package dev.lenshift.app.visual

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

// ===================================================================== primitives

/**
 * A path in graph units. [segments] means [xy] holds separate segments (x1, y1, x2, y2, …)
 * rather than one polyline; [arrow] puts an arrowhead at the end of the path or of every
 * segment. [bounded] is false for things that run off the view (curves, infinite lines),
 * so they do not decide the automatic view.
 */
class GPath(
    val xy: DoubleArray,
    val stroke: Int?,
    val width: Float = 2.5f,
    val dash: Dash = Dash.SOLID,
    val fill: Int? = null,
    val closed: Boolean = false,
    val arrow: Boolean = false,
    val segments: Boolean = false,
    val bounded: Boolean = true
)

/** How a point can be dragged: freely, along y = f(x), or around a circle. */
sealed interface Drag {
    val key: String
}

data class FreeDrag(override val key: String) : Drag
class AlongGraph(override val key: String, val f: (Double) -> Double) : Drag
class AroundCircle(override val key: String, val cx: Double, val cy: Double, val r: Double) : Drag

class GDot(val x: Double, val y: Double, val color: Int, val label: String?, val drag: Drag? = null)

enum class Anchor { ABOVE, BELOW, RIGHT, CENTER }

/** A label in graph units; [boxed] values (∫, Σ, areas, lengths) sit on a solid pill so axes never show through. */
class GText(val x: Double, val y: Double, val text: String, val color: Int, val anchor: Anchor = Anchor.CENTER, val boxed: Boolean = false)

/** An angle mark at [vertex] from the ray towards [from] to the ray towards [to]. */
class GAngle(val vertex: DoubleArray, val from: DoubleArray, val to: DoubleArray, val color: Int, val label: String, val right: Boolean)

/** Shaded cells of an inequality: [cells] is row-major with row 0 at the bottom of [view]. */
class GMask(val view: View2, val nx: Int, val ny: Int, val cells: BooleanArray, val color: Int)

class GPie(val values: DoubleArray, val labels: List<String?>, val colors: IntArray)

enum class LegendKind { LINE, AREA, DOT }

class LegendEntry(val label: String, val color: Int, val kind: LegendKind, val dash: Dash)

/** Everything one 2D graph draws for one set of slider values, drags and view. */
class Scene2D(
    val paths: List<GPath>,
    val dots: List<GDot>,
    val texts: List<GText>,
    val angles: List<GAngle>,
    val masks: List<GMask>,
    val pies: List<GPie>,
    val legend: List<LegendEntry>,
    val notes: List<String>,
    /** The shared values after resolving (sliders, then named point coordinates). */
    val values: DoubleArray
)

/**
 * Resolves a [Graph2D] into drawable geometry. Items run in order, so a construction sees
 * every point, line, circle and function defined before it; drags (by point name, or
 * "#index" for unnamed points) override where points sit.
 */
object Resolve2D {

    /** Default fill opacity for shapes, areas and bars. */
    private const val SHAPE_ALPHA = 0.22
    private const val AREA_ALPHA = 0.32

    fun resolve(
        graph: Graph2D,
        sliders: DoubleArray,
        drags: Map<String, DoubleArray>,
        view: View2,
        pxW: Float,
        pxH: Float
    ): Scene2D = Run(graph, sliders, drags, view, max(pxW, 1f), max(pxH, 1f)).run()

    private class Run(
        val graph: Graph2D,
        sliders: DoubleArray,
        val drags: Map<String, DoubleArray>,
        val view: View2,
        val pxW: Float,
        val pxH: Float
    ) {
        val values = DoubleArray(graph.shared.size).also { v -> sliders.copyInto(v, 0, 0, min(sliders.size, v.size)) }
        val slots = graph.shared.withIndex().associate { (i, n) -> n to i }
        val points = mutableMapOf<String, DoubleArray>()
        val lines = mutableMapOf<String, Pair<DoubleArray, DoubleArray>>()
        val circles = mutableMapOf<String, DoubleArray>()

        val paths = mutableListOf<GPath>()
        val dots = mutableListOf<GDot>()
        val texts = mutableListOf<GText>()
        val angles = mutableListOf<GAngle>()
        val masks = mutableListOf<GMask>()
        val pies = mutableListOf<GPie>()
        val legend = mutableListOf<LegendEntry>()
        val notes = mutableListOf<String>()
        private var paletteIndex = 0

        /** Function curves get the source as a legend label when several are drawn. */
        private val curveCount = graph.items.count { it is FnItem || it is ParametricItem || it is PolarItem || it is DerivativeItem }

        fun run(): Scene2D {
            graph.items.forEachIndexed { index, item ->
                try {
                    item(index, item)
                } catch (e: IllegalArgumentException) {
                    notes += "Item ${index + 1}: ${e.message}"
                }
            }
            return Scene2D(paths, dots, texts, angles, masks, pies, legend, notes.distinct(), values)
        }

        private fun color(style: Style): Int = style.color ?: VisualSpec.PALETTE[paletteIndex++ % VisualSpec.PALETTE.size]

        /** The fill to use: the style's (colour 0 meaning "this item's colour"), else [default] alpha. */
        private fun fill(style: Style, color: Int, defaultAlpha: Double?): Int? {
            val f = style.fill
            return when {
                f != null && (f and 0xFFFFFF) == 0 -> withAlpha(color, if ((f ushr 24) == 0) defaultAlpha ?: SHAPE_ALPHA else (f ushr 24) / 255.0)
                f != null -> f
                defaultAlpha != null -> withAlpha(color, defaultAlpha)
                else -> null
            }
        }

        private fun width(style: Style, default: Float) = style.width ?: default

        private fun item(index: Int, item: Item2) {
            val style = item.style
            val show = style.visible
            when (item) {
                is FnItem -> {
                    val c = color(style)
                    val f = item.f.bind(values)
                    if (!show) return
                    val samples = (pxW / 2).roundToInt().coerceIn(200, 1_200)
                    val polylines = if (item.ofY) {
                        val (y0, y1) = range(item.domain, view.yMin, view.yMax)
                        val swapped = View2(view.yMin, view.yMax, view.xMin, view.xMax)
                        Plot2D.sampleFunction(f, y0, y1, swapped, samples).map { swap(it) }
                    } else {
                        val (x0, x1) = range(item.domain, view.xMin, view.xMax)
                        Plot2D.sampleFunction(f, x0, x1, view, samples)
                    }
                    polylines.forEach { paths += GPath(it, c, width(style, 2.5f), style.dash, bounded = false) }
                    curveLegend(style, c, if (item.ofY) "x = ${item.f.source}" else "y = ${item.f.source}")
                }
                is ParametricItem -> {
                    val c = color(style)
                    if (!show) return
                    val fx = item.x.bind(values)
                    val fy = item.y.bind(values)
                    Plot2D.samplePath(fx, fy, item.t.from.at(values), item.t.to.at(values), 900, diagonal() * 0.5)
                        .forEach { paths += GPath(it, c, width(style, 2.5f), style.dash, bounded = false) }
                    curveLegend(style, c, "(${item.x.source}, ${item.y.source})")
                }
                is PolarItem -> {
                    val c = color(style)
                    if (!show) return
                    val r = item.r.bind(values)
                    Plot2D.samplePath({ t -> r(t) * cos(t) }, { t -> r(t) * sin(t) }, item.theta.from.at(values), item.theta.to.at(values), 900, diagonal() * 0.5)
                        .forEach { paths += GPath(it, c, width(style, 2.5f), style.dash, bounded = false) }
                    curveLegend(style, c, "r = ${item.r.source}")
                }
                is ImplicitItem -> {
                    val c = color(style)
                    if (!show) return
                    val f = item.f.bind2(values)
                    val nx = 160
                    val ny = (nx * pxH / pxW).roundToInt().coerceIn(40, 320)
                    if (item.relation != Relation.EQ) {
                        masks += GMask(view, nx, ny, Plot2D.region(f, item.relation, view, nx, ny), fill(style, c, 0.28)!!)
                    }
                    val strict = item.relation == Relation.LT || item.relation == Relation.GT
                    val segments = Plot2D.contour(f, view, nx, ny)
                    if (segments.isNotEmpty()) {
                        paths += GPath(segments, c, width(style, 2.5f), if (strict) Dash.DASHED else style.dash, segments = true, bounded = false)
                    }
                    style.label?.let { legend += LegendEntry(it, c, if (item.relation == Relation.EQ) LegendKind.LINE else LegendKind.AREA, style.dash) }
                }
                is DerivativeItem -> {
                    val c = color(style)
                    if (!show) return
                    val f = item.f.bind(values)
                    val d: (Double) -> Double = { x -> Plot2D.derivative(f, x) }
                    Plot2D.sampleFunction(d, view.xMin, view.xMax, view, (pxW / 2).roundToInt().coerceIn(200, 1_200))
                        .forEach { paths += GPath(it, c, width(style, 2.5f), style.dash, bounded = false) }
                    curveLegend(style, c, "y = (${item.f.source})′")
                }
                is ThroughItem -> {
                    val c = color(style)
                    val xy = item.points.mapNotNull { pos(it) }
                    if (!show || xy.size < 2) return
                    paths += GPath(if (item.smooth) spline(xy) else flat(xy), c, width(style, 2.5f), style.dash)
                    style.label?.let { legend += LegendEntry(it, c, LegendKind.LINE, style.dash) }
                }
                is PointItem -> point(index, item)
                is PointsItem -> {
                    val c = color(style)
                    if (!show) return
                    item.points.forEachIndexed { i, p ->
                        pos(p)?.let { dots += GDot(it[0], it[1], c, item.labels.getOrNull(i)) }
                    }
                    style.label?.let { legend += LegendEntry(it, c, LegendKind.DOT, Dash.SOLID) }
                }
                is LineItem -> {
                    val c = color(style)
                    val a = need(pos(item.a), "a line needs both points")
                    val b = need(pos(item.b), "a line needs both points")
                    item.name?.let { lines[it] = a to b }
                    if (!show) return
                    drawLine(a, b, item.kind, c, style)
                    val mid = doubleArrayOf((a[0] + b[0]) / 2, (a[1] + b[1]) / 2)
                    val label = listOfNotNull(style.label, if (item.showLength) Analysis.num(hypot(b[0] - a[0], b[1] - a[1])) else null)
                    if (label.isNotEmpty()) texts += GText(mid[0], mid[1], label.joinToString(" = "), c, Anchor.ABOVE, boxed = item.showLength)
                }
                is VectorItem -> {
                    val c = color(style)
                    val a = need(pos(item.from), "a vector needs its start and end")
                    val b = need(pos(item.to), "a vector needs its start and end")
                    if (!show) return
                    paths += GPath(doubleArrayOf(a[0], a[1], b[0], b[1]), c, width(style, 2.5f), style.dash, arrow = true)
                    style.label?.let { texts += GText(b[0], b[1], it, c, Anchor.RIGHT) }
                }
                is PerpendicularItem, is ParallelItem -> {
                    val (ref, through, name) = when (item) {
                        is PerpendicularItem -> Triple(item.line, item.through, item.name)
                        is ParallelItem -> Triple(item.line, item.through, item.name)
                        else -> return
                    }
                    val c = color(style)
                    val (a, b) = line(ref)
                    val p = need(pos(through), "the point to pass through is missing")
                    val dx = b[0] - a[0]
                    val dy = b[1] - a[1]
                    val q = if (item is PerpendicularItem) doubleArrayOf(p[0] - dy, p[1] + dx) else doubleArrayOf(p[0] + dx, p[1] + dy)
                    name?.let { lines[it] = p to q }
                    if (show) drawLine(p, q, LineKind.LINE, c, style)
                    if (show) style.label?.let { texts += GText(q[0], q[1], it, c, Anchor.RIGHT) }
                }
                is BisectorItem -> {
                    val c = color(style)
                    val a = need(pos(item.a), "bisector needs three points")
                    val v = need(pos(item.vertex), "bisector needs three points")
                    val b = need(pos(item.c), "bisector needs three points")
                    val u1 = unit(a[0] - v[0], a[1] - v[1])
                    val u2 = unit(b[0] - v[0], b[1] - v[1])
                    var dx = u1[0] + u2[0]
                    var dy = u1[1] + u2[1]
                    if (hypot(dx, dy) < 1e-9) {
                        dx = -u1[1]
                        dy = u1[0]
                    }
                    val q = doubleArrayOf(v[0] + dx, v[1] + dy)
                    item.name?.let { lines[it] = v to q }
                    if (show) drawLine(v, q, LineKind.RAY, c, style)
                }
                is PerpBisectorItem -> {
                    val c = color(style)
                    val a = need(pos(item.a), "perpendicular bisector needs two points")
                    val b = need(pos(item.b), "perpendicular bisector needs two points")
                    val m = doubleArrayOf((a[0] + b[0]) / 2, (a[1] + b[1]) / 2)
                    val q = doubleArrayOf(m[0] - (b[1] - a[1]), m[1] + (b[0] - a[0]))
                    item.name?.let { lines[it] = m to q }
                    if (show) drawLine(m, q, LineKind.LINE, c, style)
                }
                is TangentItem -> {
                    val c = color(style)
                    val f = item.f.bind(values)
                    val x0 = item.point?.let { need(pos(it), "the point is missing")[0] } ?: item.at!!.at(values)
                    val y0 = f(x0)
                    if (!y0.isFinite()) throw SpecError("the function is undefined at x = ${Analysis.num(x0)}")
                    val m = Plot2D.derivative(f, x0)
                    if (!m.isFinite()) throw SpecError("no tangent at x = ${Analysis.num(x0)}")
                    val p = doubleArrayOf(x0, y0)
                    val q = if (item.normal) doubleArrayOf(x0 - m, y0 + 1) else doubleArrayOf(x0 + 1, y0 + m)
                    item.name?.let { lines[it] = p to q }
                    if (!show) return
                    drawLine(p, q, LineKind.LINE, c, style.copy(dash = if (style.dash == Dash.SOLID) Dash.SOLID else style.dash))
                    if (item.point == null) dots += GDot(x0, y0, c, null)
                    val kind = if (item.normal) "normal" else "tangent"
                    legend += LegendEntry(style.label ?: "$kind at x = ${Analysis.num(x0)}, slope ${Analysis.num(if (item.normal) -1 / m else m)}", c, LegendKind.LINE, style.dash)
                }
                is CircleItem -> {
                    val c = color(style)
                    val center = need(pos(item.center), "circle needs a center")
                    val r = item.r?.at(values) ?: item.through?.let { t ->
                        val p = need(pos(t), "circle's point is missing")
                        hypot(p[0] - center[0], p[1] - center[1])
                    } ?: throw SpecError("circle needs a radius")
                    circle(item.name, center, abs(r), c, style)
                }
                is TriangleCircleItem -> {
                    val c = color(style)
                    val a = need(pos(item.a), "needs three points")
                    val b = need(pos(item.b), "needs three points")
                    val d = need(pos(item.c), "needs three points")
                    val circle = (if (item.inscribed) Geometry2.incircle(a, b, d) else Geometry2.circumcircle(a, b, d))
                        ?: throw SpecError("the three points are on one line")
                    circle(item.name, doubleArrayOf(circle[0], circle[1]), circle[2], c, style)
                }
                is ArcItem -> {
                    val c = color(style)
                    val center = need(pos(item.center), "arc needs a center")
                    val from = item.from?.let { pos(it) }
                    val to = item.to?.let { pos(it) }
                    val r = item.r?.at(values) ?: from?.let { hypot(it[0] - center[0], it[1] - center[1]) } ?: throw SpecError("arc needs r")
                    val a0 = from?.let { atan2(it[1] - center[1], it[0] - center[0]) } ?: Math.toRadians(item.start!!.at(values))
                    var a1 = to?.let { atan2(it[1] - center[1], it[0] - center[0]) } ?: Math.toRadians(item.end!!.at(values))
                    while (a1 <= a0) a1 += 2 * PI
                    if (!show) return
                    val n = 72
                    val xy = DoubleArray((n + 1) * 2) { k ->
                        val t = a0 + (a1 - a0) * (k / 2) / n
                        if (k % 2 == 0) center[0] + r * cos(t) else center[1] + r * sin(t)
                    }
                    if (item.sector) {
                        val closed = doubleArrayOf(center[0], center[1]) + xy
                        paths += GPath(closed, c, width(style, 2f), style.dash, fill(style, c, SHAPE_ALPHA), closed = true)
                    } else {
                        paths += GPath(xy, c, width(style, 2.5f), style.dash, fill(style, c, null))
                    }
                    style.label?.let {
                        val mid = (a0 + a1) / 2
                        texts += GText(center[0] + r * 0.6 * cos(mid), center[1] + r * 0.6 * sin(mid), it, c)
                    }
                }
                is EllipseItem -> {
                    val c = color(style)
                    val center = need(pos(item.center), "ellipse needs a center")
                    val rx = item.rx.at(values)
                    val ry = item.ry.at(values)
                    val rot = Math.toRadians(item.degrees.at(values))
                    if (!show) return
                    val n = 120
                    val xy = DoubleArray(n * 2) { k ->
                        val t = 2 * PI * (k / 2) / n
                        val ex = rx * cos(t)
                        val ey = ry * sin(t)
                        if (k % 2 == 0) center[0] + ex * cos(rot) - ey * sin(rot) else center[1] + ex * sin(rot) + ey * cos(rot)
                    }
                    paths += GPath(xy, c, width(style, 2.5f), style.dash, fill(style, c, null), closed = true)
                    style.label?.let { texts += GText(center[0], center[1] + abs(ry), it, c, Anchor.ABOVE) }
                }
                is PolygonItem -> {
                    val c = color(style)
                    val xy = item.points.map { need(pos(it), "a polygon point is missing") }
                    if (!show) return
                    val flatXy = flat(xy)
                    paths += GPath(flatXy, c, width(style, 2f), style.dash, fill(style, c, SHAPE_ALPHA), closed = true)
                    val cx = xy.sumOf { it[0] } / xy.size
                    val cy = xy.sumOf { it[1] } / xy.size
                    val label = listOfNotNull(style.label, if (item.showArea) "Area = ${Analysis.num(Geometry2.polygonArea(flatXy))}" else null)
                    if (label.isNotEmpty()) texts += GText(cx, cy, label.joinToString(", "), c, boxed = item.showArea)
                }
                is RegularPolygonItem -> {
                    val c = color(style)
                    val center = need(pos(item.center), "regularPolygon needs a center")
                    val r = item.r.at(values)
                    val start = Math.toRadians(item.degrees.at(values))
                    if (!show) return
                    val xy = DoubleArray(item.n * 2) { k ->
                        val t = start + 2 * PI * (k / 2) / item.n
                        if (k % 2 == 0) center[0] + r * cos(t) else center[1] + r * sin(t)
                    }
                    paths += GPath(xy, c, width(style, 2f), style.dash, fill(style, c, SHAPE_ALPHA), closed = true)
                    style.label?.let { texts += GText(center[0], center[1], it, c) }
                }
                is AngleItem -> {
                    val c = color(style)
                    val v = need(pos(item.vertex), "angle needs vertex, from and to")
                    val a = need(pos(item.from), "angle needs vertex, from and to")
                    val b = need(pos(item.to), "angle needs vertex, from and to")
                    if (!show) return
                    val a1 = atan2(a[1] - v[1], a[0] - v[0])
                    val a2 = atan2(b[1] - v[1], b[0] - v[0])
                    var sweep = a2 - a1
                    while (sweep <= -PI) sweep += 2 * PI
                    while (sweep > PI) sweep -= 2 * PI
                    val degrees = Math.toDegrees(abs(sweep))
                    angles += GAngle(v, a, b, c, style.label ?: formatDegrees(abs(sweep)), abs(degrees - 90) < 0.05)
                }
                is AreaItem -> {
                    val c = color(style)
                    val f = item.f.bind(values)
                    val g = item.g?.bind(values) ?: { 0.0 }
                    val a = item.x.from.at(values)
                    val b = item.x.to.at(values)
                    if (!(b > a)) throw SpecError("the area's x range is empty")
                    if (!show) return
                    val n = 240
                    val top = DoubleArray((n + 1) * 2) { k -> val x = a + (b - a) * (k / 2) / n; if (k % 2 == 0) x else clampY(f(x)) }
                    val bottom = DoubleArray((n + 1) * 2) { k -> val x = b - (b - a) * (k / 2) / n; if (k % 2 == 0) x else clampY(g(x)) }
                    paths += GPath(top + bottom, null, fill = fill(style, c, AREA_ALPHA), closed = true)
                    if (item.showValue) {
                        val value = Analysis.integral({ x -> f(x) - g(x) }, a, b)
                        val mx = (a + b) / 2
                        texts += GText(mx, (clampY(f(mx)) + clampY(g(mx))) / 2, "∫ = ${Analysis.num(value)}", c, boxed = true)
                    }
                    style.label?.let { legend += LegendEntry(it, c, LegendKind.AREA, Dash.SOLID) }
                }
                is RiemannItem -> {
                    val c = color(style)
                    val f = item.f.bind(values)
                    val a = item.x.from.at(values)
                    val b = item.x.to.at(values)
                    val n = item.n.at(values).roundToInt().coerceIn(1, 500)
                    if (!show) return
                    val (sum, pieces) = Analysis.riemann(f, a, b, n, item.type)
                    pieces.forEach { paths += GPath(it, c, 1.2f, Dash.SOLID, fill(style, c, 0.3), closed = true) }
                    if (item.showValue) texts += GText((a + b) / 2, clampY(f((a + b) / 2)), "Σ = ${Analysis.num(sum)}", c, Anchor.ABOVE, boxed = true)
                    legend += LegendEntry(style.label ?: "${item.type.name.lowercase().replaceFirstChar { it.uppercase() }} sum, n = $n", c, LegendKind.AREA, Dash.SOLID)
                }
                is MarkItem -> {
                    val c = style.color ?: 0xFFE8EAED.toInt()
                    if (!show) return
                    val f = item.f.bind(values)
                    val g = item.g?.bind(values)
                    val found: List<Pair<DoubleArray, String?>> = when (item.kind) {
                        MarkKind.ROOTS -> Analysis.roots(f, view.xMin, view.xMax).map { doubleArrayOf(it, 0.0) to null }
                        MarkKind.EXTREMA -> Analysis.extrema(f, view.xMin, view.xMax).map { (x, isMax) ->
                            doubleArrayOf(x, f(x)) to if (isMax) "max" else "min"
                        }
                        MarkKind.INTERSECTIONS -> Analysis.roots({ x -> f(x) - g!!(x) }, view.xMin, view.xMax).map { doubleArrayOf(it, f(it)) to null }
                    }
                    for ((p, kind) in found.take(40)) {
                        val coords = if (item.showCoordinates) "(${Analysis.num(p[0])}, ${Analysis.num(p[1])})" else null
                        dots += GDot(p[0], p[1], c, listOfNotNull(kind, coords).joinToString(" ").ifEmpty { null })
                    }
                }
                is FieldItem -> {
                    val c = color(style)
                    if (!show) return
                    field(item, c, style)
                }
                is BarsItem -> {
                    val c = color(style)
                    if (!show) return
                    val w = item.width?.at(values) ?: Plot2D.barWidth(item, values)
                    item.bars.forEachIndexed { i, (xNum, hNum) ->
                        val x = xNum.at(values)
                        val h = hNum.at(values)
                        if (!x.isFinite() || !h.isFinite()) return@forEachIndexed
                        val xy = doubleArrayOf(x - w / 2, 0.0, x - w / 2, h, x + w / 2, h, x + w / 2, 0.0)
                        paths += GPath(xy, c, 1f, Dash.SOLID, fill(style, c, 0.75), closed = true)
                        val label = item.labels.getOrNull(i)
                        texts += GText(x, h, label ?: Analysis.num(h), 0xFFE8EAED.toInt(), if (h >= 0) Anchor.ABOVE else Anchor.BELOW)
                    }
                    style.label?.let { legend += LegendEntry(it, c, LegendKind.AREA, Dash.SOLID) }
                }
                is PieItem -> {
                    if (!show) return
                    val vals = item.values.map { max(0.0, it.at(values)) }.toDoubleArray()
                    if (vals.sum() <= 0) throw SpecError("pie values must add up to more than 0")
                    val colors = IntArray(vals.size) { item.colors[it] ?: VisualSpec.PALETTE[it % VisualSpec.PALETTE.size] }
                    pies += GPie(vals, item.labels, colors)
                }
                is FitItem -> {
                    val c = color(style)
                    val xy = item.points.mapNotNull { pos(it) }
                    val fit = Analysis.fit(xy.map { it[0] }.toDoubleArray(), xy.map { it[1] }.toDoubleArray(), item.type)
                        ?: throw SpecError("these points can't be fitted with a ${item.type.name.lowercase()} curve")
                    if (!show) return
                    xy.forEach { dots += GDot(it[0], it[1], c, null) }
                    Plot2D.sampleFunction(fit.f, view.xMin, view.xMax, view, (pxW / 2).roundToInt().coerceIn(200, 1_200))
                        .forEach { paths += GPath(it, c, width(style, 2.5f), style.dash, bounded = false) }
                    val equation = if (item.showEquation) "${fit.equation}  (R² = ${Analysis.num(fit.r2)})" else null
                    val label = listOfNotNull(style.label, equation).joinToString(": ")
                    if (label.isNotEmpty()) legend += LegendEntry(label, c, LegendKind.LINE, style.dash)
                }
                is TextItem -> {
                    val p = need(pos(item.at), "text needs a position")
                    if (show) texts += GText(p[0], p[1], item.text, style.color ?: 0xFFE8EAED.toInt())
                }
            }
        }

        // -------------------------------------------------------------- points

        private fun point(index: Int, item: PointItem) {
            val style = item.style
            val key = item.name ?: "#$index"
            val c = color(style)
            val at = item.at
            var drag: Drag? = null
            val xy: DoubleArray = when (at) {
                is AtXY -> {
                    if (item.drag) drag = FreeDrag(key)
                    (if (item.drag) drags[key]?.copyOf() else null) ?: need(pos(at), "the point is missing")
                }
                is OnGraph -> {
                    val f = at.f.bind(values)
                    val x = (if (item.drag) drags[key]?.get(0) else null) ?: at.x.at(values)
                    if (item.drag) drag = AlongGraph(key, f)
                    doubleArrayOf(x, f(x))
                }
                is OnCircle -> {
                    val circle = circles[at.circle] ?: throw SpecError("circle '${at.circle}' is missing")
                    val degrees = (if (item.drag) drags[key]?.get(0) else null) ?: at.degrees.at(values)
                    if (item.drag) drag = AroundCircle(key, circle[0], circle[1], circle[2])
                    val t = Math.toRadians(degrees)
                    doubleArrayOf(circle[0] + circle[2] * cos(t), circle[1] + circle[2] * sin(t))
                }
                else -> need(pos(at), "the point is missing")
            }
            if (!xy[0].isFinite() || !xy[1].isFinite()) throw SpecError("the point is undefined")
            item.name?.let { setPoint(it, xy) }
            if (style.visible) dots += GDot(xy[0], xy[1], c, style.label ?: item.name, drag)
        }

        private fun setPoint(name: String, xy: DoubleArray) {
            points[name] = xy
            slots["$name.x"]?.let { values[it] = xy[0] }
            slots["$name.y"]?.let { values[it] = xy[1] }
        }

        fun pos(p: Point2): DoubleArray? = when (p) {
            is AtXY -> doubleArrayOf(p.x.at(values), p.y.at(values)).takeIf { it[0].isFinite() && it[1].isFinite() }
            is PointRef -> points[p.name]
            is MidpointOf -> {
                val a = pos(p.a)
                val b = pos(p.b)
                if (a == null || b == null) null else doubleArrayOf((a[0] + b[0]) / 2, (a[1] + b[1]) / 2)
            }
            is OnGraph -> {
                val x = p.x.at(values)
                doubleArrayOf(x, p.f.bind(values)(x)).takeIf { it[1].isFinite() }
            }
            is OnCircle -> circles[p.circle]?.let { c ->
                val t = Math.toRadians(p.degrees.at(values))
                doubleArrayOf(c[0] + c[2] * cos(t), c[1] + c[2] * sin(t))
            }
            is IntersectionOf -> intersections(p.a, p.b).getOrNull(p.index)
        }

        private fun intersections(a: String, b: String): List<DoubleArray> {
            val la = lines[a]
            val lb = lines[b]
            val ca = circles[a]
            val cb = circles[b]
            return when {
                la != null && lb != null -> listOfNotNull(Geometry2.lineLine(la.first, la.second, lb.first, lb.second))
                la != null && cb != null -> Geometry2.lineCircle(la.first, la.second, cb, cb[2])
                ca != null && lb != null -> Geometry2.lineCircle(lb.first, lb.second, ca, ca[2])
                ca != null && cb != null -> Geometry2.circleCircle(ca, ca[2], cb, cb[2])
                else -> emptyList()
            }
        }

        private fun line(ref: LineRef): Pair<DoubleArray, DoubleArray> = when (ref) {
            is NamedLine -> lines[ref.name] ?: throw SpecError("line '${ref.name}' is missing")
            is TwoPoints -> need(pos(ref.a), "a line point is missing") to need(pos(ref.b), "a line point is missing")
        }

        // ---------------------------------------------------------------- shapes

        private fun drawLine(a: DoubleArray, b: DoubleArray, kind: LineKind, c: Int, style: Style) {
            val clipped = Geometry2.clip(a, b, kind, view) ?: return
            paths += GPath(clipped, c, width(style, if (kind == LineKind.SEGMENT) 2.5f else 2f), style.dash, bounded = kind == LineKind.SEGMENT)
        }

        private fun circle(name: String?, center: DoubleArray, r: Double, c: Int, style: Style) {
            name?.let { circles[it] = doubleArrayOf(center[0], center[1], r) }
            if (!style.visible) return
            val n = 144
            val xy = DoubleArray(n * 2) { k ->
                val t = 2 * PI * (k / 2) / n
                if (k % 2 == 0) center[0] + r * cos(t) else center[1] + r * sin(t)
            }
            paths += GPath(xy, c, width(style, 2.5f), style.dash, fill(style, c, null), closed = true)
            style.label?.let { texts += GText(center[0] + r * 0.72, center[1] + r * 0.72, it, c, Anchor.RIGHT) }
        }

        /** A slope field (short strokes) or vector field (arrows) on a grid across the view. */
        private fun field(item: FieldItem, c: Int, style: Style) {
            val cols = 17
            val rows = (cols * pxH / pxW).roundToInt().coerceIn(6, 40)
            val sx = pxW / view.width
            val sy = pxH / view.height
            val cellPx = min(pxW / cols, pxH / rows).toDouble()
            val out = DoubleList()
            if (item.slope != null) {
                val f = item.slope.bind2(values)
                for (i in 0 until cols) for (j in 0 until rows) {
                    val x = view.xMin + view.width * (i + 0.5) / cols
                    val y = view.yMin + view.height * (j + 0.5) / rows
                    val m = f(x, y)
                    if (!m.isFinite()) continue
                    stroke(out, x, y, 1.0, m, cellPx * 0.36, sx, sy)
                }
                paths += GPath(out.toArray(), c, width(style, 1.6f), style.dash, segments = true, bounded = false)
            } else {
                val fx = item.vx!!.bind2(values)
                val fy = item.vy!!.bind2(values)
                val vectors = mutableListOf<DoubleArray>()
                var largest = 0.0
                for (i in 0 until cols) for (j in 0 until rows) {
                    val x = view.xMin + view.width * (i + 0.5) / cols
                    val y = view.yMin + view.height * (j + 0.5) / rows
                    val vx = fx(x, y)
                    val vy = fy(x, y)
                    if (!vx.isFinite() || !vy.isFinite()) continue
                    val length = hypot(vx * sx, vy * sy)
                    largest = max(largest, length)
                    vectors += doubleArrayOf(x, y, vx, vy, length)
                }
                for (v in vectors) {
                    if (v[4] < 1e-12) continue
                    // Longer arrows for stronger vectors, but never invisible or overlapping.
                    val px = cellPx * (0.3 + 0.55 * sqrt(v[4] / largest))
                    stroke(out, v[0], v[1], v[2], v[3], px / 2, sx, sy)
                }
                paths += GPath(out.toArray(), c, width(style, 1.6f), style.dash, arrow = true, segments = true, bounded = false)
            }
            style.label?.let { legend += LegendEntry(it, c, LegendKind.LINE, style.dash) }
        }

        /** A stroke centred on (x, y) along (dx, dy), [halfPx] long each way on screen. */
        private fun stroke(out: DoubleList, x: Double, y: Double, dx: Double, dy: Double, halfPx: Double, sx: Double, sy: Double) {
            val screenX = dx * sx
            val screenY = dy * sy
            val len = hypot(screenX, screenY)
            if (len < 1e-12) return
            val ux = screenX / len * halfPx / sx
            val uy = screenY / len * halfPx / sy
            out.add(x - ux)
            out.add(y - uy)
            out.add(x + ux)
            out.add(y + uy)
        }

        // --------------------------------------------------------------- helpers

        private fun curveLegend(style: Style, color: Int, fallback: String) {
            val label = style.label ?: if (curveCount > 1) fallback else null
            label?.let { legend += LegendEntry(it, color, LegendKind.LINE, style.dash) }
        }

        private fun range(domain: Span?, min: Double, max: Double): Pair<Double, Double> {
            if (domain == null) return min to max
            val a = domain.from.at(values)
            val b = domain.to.at(values)
            return max(a, min) to min(b, max)
        }

        private fun diagonal() = hypot(view.width, view.height)

        /** Keeps fills inside a sane band around the view so a pole cannot blow up a path. */
        private fun clampY(y: Double): Double {
            val pad = view.height * 4
            return if (y.isFinite()) y.coerceIn(view.yMin - pad, view.yMax + pad) else 0.0
        }

        private fun swap(xy: DoubleArray) = DoubleArray(xy.size) { k -> if (k % 2 == 0) xy[k + 1] else xy[k - 1] }

        private fun flat(xy: List<DoubleArray>) = DoubleArray(xy.size * 2) { k -> xy[k / 2][k % 2] }

        /** A Catmull–Rom spline through the points, 16 steps between each pair. */
        private fun spline(p: List<DoubleArray>): DoubleArray {
            val out = DoubleList()
            for (i in 0 until p.size - 1) {
                val p0 = p[max(0, i - 1)]
                val p1 = p[i]
                val p2 = p[i + 1]
                val p3 = p[min(p.size - 1, i + 2)]
                for (s in 0 until 16) {
                    val t = s / 16.0
                    val t2 = t * t
                    val t3 = t2 * t
                    for (k in 0..1) {
                        out.add(0.5 * (2 * p1[k] + (-p0[k] + p2[k]) * t + (2 * p0[k] - 5 * p1[k] + 4 * p2[k] - p3[k]) * t2 + (-p0[k] + 3 * p1[k] - 3 * p2[k] + p3[k]) * t3))
                    }
                }
            }
            out.add(p.last()[0])
            out.add(p.last()[1])
            return out.toArray()
        }

        private fun unit(x: Double, y: Double): DoubleArray {
            val l = hypot(x, y)
            return if (l < 1e-15) doubleArrayOf(0.0, 0.0) else doubleArrayOf(x / l, y / l)
        }

        private fun <T> need(value: T?, message: String): T = value ?: throw SpecError(message)
    }

    /** [argb] with alpha [opacity] (0..1). */
    fun withAlpha(argb: Int, opacity: Double): Int = ((opacity * 255).roundToInt().coerceIn(0, 255) shl 24) or (argb and 0xFFFFFF)

    /** "53.1°" for an angle in radians. */
    fun formatDegrees(radians: Double): String {
        val deg = Math.toDegrees(radians)
        val text = if (abs(deg - Math.round(deg)) < 0.05) "${Math.round(deg)}" else "%.1f".format(java.util.Locale.ROOT, deg)
        return "$text°"
    }
}
