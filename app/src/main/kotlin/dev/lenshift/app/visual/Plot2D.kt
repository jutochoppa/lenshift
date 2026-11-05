package dev.lenshift.app.visual

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

/** The visible window of a 2D graph, in graph units. */
data class View2(val xMin: Double, val xMax: Double, val yMin: Double, val yMax: Double) {
    val width: Double get() = xMax - xMin
    val height: Double get() = yMax - yMin

    /** Zoomed by [factor] (above 1 zooms in) about the graph point ([cx], [cy]). */
    fun zoomed(factor: Double, cx: Double, cy: Double): View2 {
        val f = 1 / factor
        return View2(cx + (xMin - cx) * f, cx + (xMax - cx) * f, cy + (yMin - cy) * f, cy + (yMax - cy) * f)
    }

    fun panned(dx: Double, dy: Double) = View2(xMin + dx, xMax + dx, yMin + dy, yMax + dy)

    /** Widened along one axis so a unit is equally long on both, for a [pxW]×[pxH] canvas. */
    fun equalised(pxW: Float, pxH: Float): View2 {
        if (pxW <= 0 || pxH <= 0) return this
        val perPx = max(width / pxW, height / pxH)
        val w = perPx * pxW
        val h = perPx * pxH
        val cx = (xMin + xMax) / 2
        val cy = (yMin + yMax) / 2
        return View2(cx - w / 2, cx + w / 2, cy - h / 2, cy + h / 2)
    }
}

/**
 * The numeric side of 2D graphs: curve sampling that breaks at asymptotes, implicit
 * curves by marching squares, inequality regions, ticks and the automatic view. Pure, so
 * every rule here is unit-tested.
 */
object Plot2D {

    /** Total function evaluations one curve may spend on refining steep parts. */
    private const val REFINE_BUDGET = 20_000
    private const val MAX_DEPTH = 10

    /**
     * Polylines (x0, y0, x1, y1, …) of y = f(x) over [x0, x1]. A line is broken where f is
     * undefined and at asymptotes: a jump that stays larger than the view however finely
     * the interval is split. Steep but continuous parts are refined instead.
     */
    fun sampleFunction(f: (Double) -> Double, x0: Double, x1: Double, view: View2, samples: Int): List<DoubleArray> {
        val out = mutableListOf<DoubleArray>()
        if (!(x1 > x0) || !x0.isFinite() || !x1.isFinite()) return out
        val n = samples.coerceIn(16, 4_000)
        val jump = view.height * 1.5
        val current = DoubleList()
        var budget = REFINE_BUDGET

        fun flush() {
            if (current.size >= 4) out += current.toArray()
            current.clear()
        }

        fun add(x: Double, y: Double) {
            current.add(x)
            current.add(y)
        }

        fun refine(a: Double, fa: Double, b: Double, fb: Double, depth: Int) {
            val aOk = fa.isFinite()
            val bOk = fb.isFinite()
            if (!aOk && !bOk) {
                flush()
                return
            }
            if (aOk && bOk && abs(fb - fa) <= jump) {
                add(b, fb)
                return
            }
            if (depth >= MAX_DEPTH || budget <= 0) {
                // Still a jump (or an edge of the domain) at the finest step: break here.
                flush()
                if (bOk) add(b, fb)
                return
            }
            val m = (a + b) / 2
            val fm = f(m)
            budget--
            refine(a, fa, m, fm, depth + 1)
            refine(m, fm, b, fb, depth + 1)
        }

        val dx = (x1 - x0) / n
        var prevX = x0
        var prevY = f(x0)
        if (prevY.isFinite()) add(prevX, prevY)
        for (i in 1..n) {
            val x = if (i == n) x1 else x0 + i * dx
            val y = f(x)
            refine(prevX, prevY, x, y, 0)
            prevX = x
            prevY = y
        }
        flush()
        return out
    }

    /**
     * Polylines of a parametric path (x(t), y(t)), broken where it is undefined or jumps
     * further than [breakDistance].
     */
    fun samplePath(
        fx: (Double) -> Double,
        fy: (Double) -> Double,
        t0: Double,
        t1: Double,
        samples: Int,
        breakDistance: Double
    ): List<DoubleArray> {
        val out = mutableListOf<DoubleArray>()
        if (!t0.isFinite() || !t1.isFinite() || t0 == t1) return out
        val n = samples.coerceIn(16, 4_000)
        val current = DoubleList()
        var px = Double.NaN
        var py = Double.NaN
        for (i in 0..n) {
            val t = t0 + (t1 - t0) * i / n
            val x = fx(t)
            val y = fy(t)
            val ok = x.isFinite() && y.isFinite()
            val jumped = ok && px.isFinite() && hypot(x - px, y - py) > breakDistance
            if (!ok || jumped) {
                if (current.size >= 4) out += current.toArray()
                current.clear()
            }
            if (ok) {
                current.add(x)
                current.add(y)
            }
            px = if (ok) x else Double.NaN
            py = if (ok) y else Double.NaN
        }
        if (current.size >= 4) out += current.toArray()
        return out
    }

    /**
     * Line segments (x1, y1, x2, y2, …) where f(x, y) = 0, by marching squares on an
     * [nx]×[ny] grid. Sign changes across a pole (tan, 1/x) are dropped: there f does not
     * get small between the corners.
     */
    fun contour(f: (Double, Double) -> Double, view: View2, nx: Int, ny: Int): DoubleArray {
        val dx = view.width / nx
        val dy = view.height / ny
        val values = Array(ny + 1) { j ->
            val y = view.yMin + j * dy
            DoubleArray(nx + 1) { i -> f(view.xMin + i * dx, y) }
        }
        val out = DoubleList()

        fun emit(x1: Double, y1: Double, x2: Double, y2: Double, cornerMax: Double) {
            val mid = f((x1 + x2) / 2, (y1 + y2) / 2)
            if (!mid.isFinite() || abs(mid) > cornerMax) return
            out.add(x1); out.add(y1); out.add(x2); out.add(y2)
        }

        for (j in 0 until ny) {
            val y0 = view.yMin + j * dy
            val y1 = y0 + dy
            for (i in 0 until nx) {
                val a = values[j][i]          // bottom-left
                val b = values[j][i + 1]      // bottom-right
                val c = values[j + 1][i + 1]  // top-right
                val d = values[j + 1][i]      // top-left
                if (!a.isFinite() || !b.isFinite() || !c.isFinite() || !d.isFinite()) continue
                val case = (if (a > 0) 1 else 0) or (if (b > 0) 2 else 0) or (if (c > 0) 4 else 0) or (if (d > 0) 8 else 0)
                if (case == 0 || case == 15) continue
                val x0 = view.xMin + i * dx
                val x1 = x0 + dx
                val cornerMax = maxOf(abs(a), abs(b), abs(c), abs(d))

                // Where the contour crosses each edge.
                fun bottom() = doubleArrayOf(x0 + dx * a / (a - b), y0)
                fun right() = doubleArrayOf(x1, y0 + dy * b / (b - c))
                fun top() = doubleArrayOf(x0 + dx * d / (d - c), y1)
                fun left() = doubleArrayOf(x0, y0 + dy * a / (a - d))
                fun seg(p: DoubleArray, q: DoubleArray) = emit(p[0], p[1], q[0], q[1], cornerMax)

                when (case) {
                    1, 14 -> seg(left(), bottom())
                    2, 13 -> seg(bottom(), right())
                    3, 12 -> seg(left(), right())
                    4, 11 -> seg(right(), top())
                    6, 9 -> seg(bottom(), top())
                    7, 8 -> seg(left(), top())
                    5, 10 -> {
                        // Saddle: the centre decides which corners connect.
                        val centre = f(x0 + dx / 2, y0 + dy / 2) > 0
                        val positiveDiagonal = (case == 5) == centre
                        if (positiveDiagonal) {
                            seg(bottom(), right())
                            seg(top(), left())
                        } else {
                            seg(left(), bottom())
                            seg(right(), top())
                        }
                    }
                }
            }
        }
        return out.toArray()
    }

    /**
     * Cells (row-major, row 0 at the bottom) of an [nx]×[ny] grid whose centre satisfies
     * f(x, y) [relation] 0, or, for [Relation.HOLDS], where the condition f is true.
     */
    fun region(f: (Double, Double) -> Double, relation: Relation, view: View2, nx: Int, ny: Int): BooleanArray {
        val dx = view.width / nx
        val dy = view.height / ny
        return BooleanArray(nx * ny) { k ->
            val i = k % nx
            val j = k / nx
            val v = f(view.xMin + (i + 0.5) * dx, view.yMin + (j + 0.5) * dy)
            v.isFinite() && when (relation) {
                Relation.LT -> v < 0
                Relation.LE -> v <= 0
                Relation.GT -> v > 0
                Relation.GE -> v >= 0
                Relation.HOLDS -> v != 0.0
                Relation.EQ -> false
            }
        }
    }

    /** f'(x) by a central difference scaled to x. */
    fun derivative(f: (Double) -> Double, x: Double): Double {
        val h = 1e-5 * max(1.0, abs(x))
        return (f(x + h) - f(x - h)) / (2 * h)
    }

    /** A step of 1, 2 or 5 times a power of ten giving at most about [maxTicks] ticks. */
    fun niceStep(span: Double, maxTicks: Int): Double {
        if (!(span > 0) || !span.isFinite()) return 1.0
        val raw = span / maxTicks.coerceAtLeast(1)
        val magnitude = 10.0.pow(floor(log10(raw)))
        val normalised = raw / magnitude
        val nice = when {
            normalised <= 1 -> 1.0
            normalised <= 2 -> 2.0
            normalised <= 5 -> 5.0
            else -> 10.0
        }
        return nice * magnitude
    }

    /** Multiples of [step] within [min]..[max], with values near zero snapped to 0. */
    fun ticks(min: Double, max: Double, step: Double): List<Double> {
        if (!(step > 0) || !min.isFinite() || !max.isFinite()) return emptyList()
        val first = ceil(min / step)
        val out = mutableListOf<Double>()
        var k = first
        while (out.size < 200) {
            val v = k * step
            if (v > max + step * 1e-9) break
            out += if (abs(v) < step * 1e-9) 0.0 else v
            k += 1
        }
        return out
    }

    /** A tick value with only the decimals [step] needs, and a true minus sign. */
    fun formatTick(value: Double, step: Double): String {
        val decimals = (-floor(log10(step) + 1e-9)).toInt().coerceIn(0, 8)
        var text = "%.${decimals}f".format(java.util.Locale.ROOT, value)
        if (text.contains('.')) text = text.trimEnd('0').trimEnd('.')
        if (text == "-0") text = "0"
        return text.replace('-', '−')
    }

    /**
     * The view to start from: the spec's ranges where given, otherwise what the resolved
     * [scene] places (points, shapes, labels), plus sampled parametric curves and function
     * values over the x range, padded and stretched to show the axes when they are close.
     * Functions are judged by their middle 96% of values, so an asymptote does not flatten
     * the rest of the graph.
     */
    fun autoView(graph: Graph2D, scene: Scene2D): View2 {
        val p = scene.values
        val xs = Range()
        val ys = Range()
        scene.dots.forEach { xs.add(it.x); ys.add(it.y) }
        scene.texts.forEach { xs.add(it.x); ys.add(it.y) }
        scene.angles.forEach { a -> listOf(a.vertex, a.from, a.to).forEach { xs.add(it[0]); ys.add(it[1]) } }
        scene.paths.filter { it.bounded }.forEach { path ->
            var k = 0
            while (k + 1 < path.xy.size) {
                xs.add(path.xy[k])
                ys.add(path.xy[k + 1])
                k += 2
            }
        }
        val functions = mutableListOf<(Double) -> Double>()
        var hasImplicit = false
        for (item in graph.items) {
            when (item) {
                is FnItem -> if (!item.ofY) {
                    functions += item.f.bind(p)
                    item.domain?.let { xs.add(it.from.at(p)); xs.add(it.to.at(p)) }
                }
                is AreaItem -> {
                    functions += item.f.bind(p)
                    item.g?.let { functions += it.bind(p) }
                    xs.add(item.x.from.at(p)); xs.add(item.x.to.at(p))
                }
                is RiemannItem -> {
                    functions += item.f.bind(p)
                    xs.add(item.x.from.at(p)); xs.add(item.x.to.at(p))
                }
                is TangentItem -> functions += item.f.bind(p)
                is MarkItem -> {
                    functions += item.f.bind(p)
                    item.g?.let { functions += it.bind(p) }
                }
                is ParametricItem -> pathPoints(item.x.bind(p), item.y.bind(p), item.t.from.at(p), item.t.to.at(p), xs, ys)
                is PolarItem -> {
                    val r = item.r.bind(p)
                    pathPoints({ t -> r(t) * cos(t) }, { t -> r(t) * sin(t) }, item.theta.from.at(p), item.theta.to.at(p), xs, ys)
                }
                is ImplicitItem -> hasImplicit = true
                else -> Unit
            }
        }

        // ---- x
        val specX = graph.x?.let { it.from.at(p) to it.to.at(p) }?.takeIf { (a, b) -> a.isFinite() && b.isFinite() && b > a }
        val (xMin, xMax) = specX ?: when {
            xs.valid && xs.span > 0 -> pad(xs.min, xs.max)
            xs.valid -> xs.min - 5 to xs.max + 5
            else -> -10.0 to 10.0
        }.let { (a, b) -> includeZero(a, b) }

        // ---- y: geometry plus function values over the x range
        if (functions.isNotEmpty()) {
            val values = mutableListOf<Double>()
            for (f in functions) {
                for (k in 0..240) {
                    val y = f(xMin + (xMax - xMin) * k / 240)
                    if (y.isFinite()) values += y
                }
            }
            if (values.isNotEmpty()) {
                values.sort()
                ys.add(values[(values.size * 0.02).toInt().coerceAtMost(values.lastIndex)])
                ys.add(values[(values.size * 0.98).toInt().coerceAtMost(values.lastIndex)])
            }
        }
        val specY = graph.y?.let { it.from.at(p) to it.to.at(p) }?.takeIf { (a, b) -> a.isFinite() && b.isFinite() && b > a }
        val (yMin, yMax) = specY ?: when {
            ys.valid && ys.span > 0 -> pad(ys.min, ys.max).let { (a, b) -> includeZero(a, b) }
            ys.valid -> includeZero(ys.min - 1, ys.max + 1)
            hasImplicit || functions.isEmpty() -> (xMax - xMin).let { w -> -w / 2 to w / 2 }
            else -> -10.0 to 10.0
        }
        return View2(xMin, xMax, yMin, yMax)
    }

    /** Whether one unit should be as long on both axes: geometry yes, function and data plots no. */
    fun wantsEqualAspect(graph: Graph2D): Boolean {
        graph.equalAspect?.let { return it }
        val geometry = graph.items.any {
            it is CircleItem || it is TriangleCircleItem || it is ArcItem || it is EllipseItem || it is PolygonItem ||
                it is RegularPolygonItem || it is AngleItem || it is VectorItem || it is PerpendicularItem ||
                it is ParallelItem || it is BisectorItem || it is PerpBisectorItem
        }
        val plots = graph.items.any {
            it is FnItem || it is AreaItem || it is BarsItem || it is TangentItem || it is ParametricItem ||
                it is PointsItem || it is RiemannItem || it is FitItem || it is DerivativeItem || it is MarkItem ||
                it is ThroughItem
        }
        val curves = graph.items.any { it is ImplicitItem || it is PolarItem || it is LineItem || it is PointItem }
        return geometry || (curves && !plots)
    }

    /** Bars fill most of the gap between neighbours. */
    fun barWidth(item: BarsItem, p: DoubleArray): Double {
        val xs = item.bars.map { it.first.at(p) }.filter { it.isFinite() }.sorted()
        val gap = xs.zipWithNext { a, b -> b - a }.filter { it > 0 }.minOrNull() ?: 1.0
        return gap * 0.7
    }

    private fun pathPoints(fx: (Double) -> Double, fy: (Double) -> Double, t0: Double, t1: Double, xs: Range, ys: Range) {
        if (!t0.isFinite() || !t1.isFinite()) return
        for (k in 0..200) {
            val t = t0 + (t1 - t0) * k / 200
            val x = fx(t)
            val y = fy(t)
            if (x.isFinite() && y.isFinite() && abs(x) < 1e6 && abs(y) < 1e6) {
                xs.add(x)
                ys.add(y)
            }
        }
    }

    private fun pad(min: Double, max: Double): Pair<Double, Double> {
        val margin = (max - min) * 0.08
        return (min - margin) to (max + margin)
    }

    /** Stretches a range to show the axis when zero is within 35% of its span. */
    private fun includeZero(min: Double, max: Double): Pair<Double, Double> {
        val span = max - min
        return when {
            min > 0 && min < span * 0.35 -> (-span * 0.05) to max
            max < 0 && -max < span * 0.35 -> min to (span * 0.05)
            else -> min to max
        }
    }

    private class Range {
        var min = Double.POSITIVE_INFINITY
        var max = Double.NEGATIVE_INFINITY
        val valid get() = min <= max
        val span get() = max - min

        fun add(v: Double) {
            if (!v.isFinite()) return
            min = min(min, v)
            max = max(max, v)
        }
    }
}

/** A growable list of doubles without boxing. */
internal class DoubleList {
    private var data = DoubleArray(64)
    var size = 0
        private set

    fun add(v: Double) {
        if (size == data.size) data = data.copyOf(size * 2)
        data[size++] = v
    }

    fun clear() {
        size = 0
    }

    fun toArray(): DoubleArray = data.copyOf(size)
}
