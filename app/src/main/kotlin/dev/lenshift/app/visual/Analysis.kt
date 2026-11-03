package dev.lenshift.app.visual

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Numerical analysis for graphs: roots, extrema, integrals, Riemann sums and curve fits.
 * Everything works on samples of a function over an interval, so a pole or a gap in the
 * domain is skipped rather than reported as a root or an extremum.
 */
object Analysis {

    /**
     * Zeros of f on [a, b]: sign changes refined by bisection (a pole, where |f| grows
     * instead of shrinking, is skipped) and touching zeros such as (x − 1)², found as
     * local minima of |f| that reach zero.
     */
    fun roots(f: (Double) -> Double, a: Double, b: Double, samples: Int = 800): List<Double> {
        if (!(b > a)) return emptyList()
        val n = samples.coerceIn(16, 10_000)
        val xs = DoubleArray(n + 1) { a + (b - a) * it / n }
        val ys = DoubleArray(n + 1) { f(xs[it]) }
        val scale = ys.filter { it.isFinite() }.maxOfOrNull { abs(it) }?.coerceAtLeast(1.0) ?: 1.0
        val found = mutableListOf<Double>()
        val tolerance = (b - a) * 1e-7

        fun add(x: Double) {
            if (found.none { abs(it - x) < (b - a) * 1e-5 }) found += x
        }

        for (i in 0 until n) {
            val ya = ys[i]
            val yb = ys[i + 1]
            if (!ya.isFinite() || !yb.isFinite()) continue
            if (ya == 0.0) {
                add(xs[i])
                continue
            }
            if (ya * yb < 0) {
                var lo = xs[i]
                var hi = xs[i + 1]
                var flo = ya
                repeat(80) {
                    val mid = (lo + hi) / 2
                    val fm = f(mid)
                    if (!fm.isFinite()) return@repeat
                    if (flo * fm <= 0) hi = mid else {
                        lo = mid
                        flo = fm
                    }
                }
                val root = (lo + hi) / 2
                val fr = f(root)
                // A real zero gets small; a pole between opposite signs does not.
                if (fr.isFinite() && abs(fr) <= max(abs(ya), abs(yb))) add(root)
            }
        }
        if (ys[n] == 0.0) add(xs[n])

        // Touching zeros: |f| dips to (nearly) zero without changing sign.
        for (i in 1 until n) {
            val y0 = ys[i - 1]
            val y1 = ys[i]
            val y2 = ys[i + 1]
            if (!y0.isFinite() || !y1.isFinite() || !y2.isFinite()) continue
            if (y0 * y1 < 0 || y1 * y2 < 0) continue
            if (abs(y1) <= abs(y0) && abs(y1) <= abs(y2) && abs(y1) < scale * 1e-3) {
                val x = minimise({ abs(f(it)) }, xs[i - 1], xs[i + 1])
                if (abs(f(x)) < scale * 1e-9 + tolerance) add(x)
            }
        }
        return found.sorted()
    }

    /** Local maxima and minima of f inside (a, b), as (x, isMaximum). */
    fun extrema(f: (Double) -> Double, a: Double, b: Double, samples: Int = 800): List<Pair<Double, Boolean>> {
        if (!(b > a)) return emptyList()
        val n = samples.coerceIn(16, 10_000)
        val xs = DoubleArray(n + 1) { a + (b - a) * it / n }
        val ys = DoubleArray(n + 1) { f(xs[it]) }
        val out = mutableListOf<Pair<Double, Boolean>>()
        for (i in 1 until n) {
            val y0 = ys[i - 1]
            val y1 = ys[i]
            val y2 = ys[i + 1]
            if (!y0.isFinite() || !y1.isFinite() || !y2.isFinite()) continue
            val isMax = y1 > y0 && y1 >= y2
            val isMin = y1 < y0 && y1 <= y2
            if (!isMax && !isMin) continue
            val x = if (isMax) minimise({ -f(it) }, xs[i - 1], xs[i + 1]) else minimise(f, xs[i - 1], xs[i + 1])
            if (out.none { abs(it.first - x) < (b - a) * 1e-5 }) out += x to isMax
        }
        return out
    }

    /** ∫ f from a to b by Simpson's rule; NaN where f is undefined on the interval. */
    fun integral(f: (Double) -> Double, a: Double, b: Double, n: Int = 1_000): Double {
        if (a == b) return 0.0
        val steps = (n + n % 2).coerceAtLeast(2)
        val h = (b - a) / steps
        var sum = f(a) + f(b)
        for (i in 1 until steps) {
            sum += (if (i % 2 == 1) 4 else 2) * f(a + i * h)
        }
        return sum * h / 3
    }

    /**
     * A Riemann sum of f over [a, b] with [n] pieces: the sum and each piece as a polygon
     * (x, y pairs) for drawing.
     */
    fun riemann(f: (Double) -> Double, a: Double, b: Double, n: Int, type: RiemannType): Pair<Double, List<DoubleArray>> {
        val count = n.coerceIn(1, 500)
        val w = (b - a) / count
        var sum = 0.0
        val pieces = mutableListOf<DoubleArray>()
        for (i in 0 until count) {
            val x0 = a + i * w
            val x1 = x0 + w
            when (type) {
                RiemannType.TRAPEZOID -> {
                    val y0 = f(x0)
                    val y1 = f(x1)
                    sum += (y0 + y1) / 2 * w
                    pieces += doubleArrayOf(x0, 0.0, x0, y0, x1, y1, x1, 0.0)
                }
                else -> {
                    val sampleX = when (type) {
                        RiemannType.LEFT -> x0
                        RiemannType.RIGHT -> x1
                        else -> (x0 + x1) / 2
                    }
                    val h = f(sampleX)
                    sum += h * w
                    pieces += doubleArrayOf(x0, 0.0, x0, h, x1, h, x1, 0.0)
                }
            }
        }
        return sum to pieces
    }

    /** A fitted curve: its equation as text, the function, and R² of the fit. */
    class Fit(val equation: String, val f: (Double) -> Double, val r2: Double)

    /** Least-squares fit of [type] to the points, or null when the data cannot be fitted. */
    fun fit(xs: DoubleArray, ys: DoubleArray, type: FitType): Fit? {
        val pairs = xs.indices.filter { xs[it].isFinite() && ys[it].isFinite() }
        if (pairs.size < 2) return null
        val x = DoubleArray(pairs.size) { xs[pairs[it]] }
        val y = DoubleArray(pairs.size) { ys[pairs[it]] }
        val fit: Pair<String, (Double) -> Double> = when (type) {
            FitType.LINEAR, FitType.QUADRATIC, FitType.CUBIC -> {
                val degree = when (type) {
                    FitType.LINEAR -> 1
                    FitType.QUADRATIC -> 2
                    else -> 3
                }
                if (x.size <= degree) return null
                val c = polynomial(x, y, degree) ?: return null
                polynomialText(c) to { t -> c.indices.sumOf { k -> c[k] * t.pow(k) } }
            }
            FitType.EXPONENTIAL -> {
                if (y.any { it <= 0 }) return null
                val (b0, b1) = line(x, y.map { ln(it) }.toDoubleArray()) ?: return null
                val a = exp(b0)
                "y = ${num(a)}·e^(${num(b1)}x)" to { t -> a * exp(b1 * t) }
            }
            FitType.LOGARITHMIC -> {
                if (x.any { it <= 0 }) return null
                val (b0, b1) = line(x.map { ln(it) }.toDoubleArray(), y) ?: return null
                "y = ${num(b0)} ${sign(b1)} ${num(abs(b1))}·ln x" to { t -> b0 + b1 * ln(t) }
            }
            FitType.POWER -> {
                if (x.any { it <= 0 } || y.any { it <= 0 }) return null
                val (b0, b1) = line(x.map { ln(it) }.toDoubleArray(), y.map { ln(it) }.toDoubleArray()) ?: return null
                val a = exp(b0)
                "y = ${num(a)}·x^${num(b1)}" to { t -> a * t.pow(b1) }
            }
        }
        val mean = y.average()
        val total = y.sumOf { (it - mean) * (it - mean) }
        val residual = y.indices.sumOf { (y[it] - fit.second(x[it])).let { d -> d * d } }
        val r2 = if (total > 0) 1 - residual / total else 1.0
        return Fit(fit.first, fit.second, r2)
    }

    /** Intercept and slope of the least-squares line. */
    private fun line(x: DoubleArray, y: DoubleArray): Pair<Double, Double>? =
        polynomial(x, y, 1)?.let { it[0] to it[1] }

    /** Coefficients c0…c[degree] of the least-squares polynomial, by the normal equations. */
    private fun polynomial(x: DoubleArray, y: DoubleArray, degree: Int): DoubleArray? {
        val m = degree + 1
        val a = Array(m) { DoubleArray(m + 1) }
        for (row in 0 until m) {
            for (col in 0 until m) a[row][col] = x.sumOf { it.pow(row + col) }
            a[row][m] = x.indices.sumOf { y[it] * x[it].pow(row) }
        }
        // Gaussian elimination with partial pivoting.
        for (col in 0 until m) {
            val pivot = (col until m).maxByOrNull { abs(a[it][col]) } ?: return null
            if (abs(a[pivot][col]) < 1e-12) return null
            val tmp = a[col]
            a[col] = a[pivot]
            a[pivot] = tmp
            for (row in 0 until m) {
                if (row == col) continue
                val factor = a[row][col] / a[col][col]
                for (k in col..m) a[row][k] -= factor * a[col][k]
            }
        }
        return DoubleArray(m) { a[it][m] / a[it][it] }
    }

    private fun polynomialText(c: DoubleArray): String {
        val terms = (c.indices.reversed()).filter { abs(c[it]) > 1e-12 }.mapIndexed { i, k ->
            val value = c[k]
            val magnitude = num(abs(value))
            val body = when (k) {
                0 -> magnitude
                1 -> if (magnitude == "1") "x" else "${magnitude}x"
                2 -> if (magnitude == "1") "x²" else "${magnitude}x²"
                else -> if (magnitude == "1") "x³" else "${magnitude}x³"
            }
            when {
                i == 0 -> if (value < 0) "−$body" else body
                else -> "${sign(value)} $body"
            }
        }
        return "y = " + terms.joinToString(" ").ifEmpty { "0" }
    }

    private fun sign(v: Double) = if (v < 0) "−" else "+"

    /** Three significant figures, without trailing zeros, with a true minus sign. */
    fun num(v: Double): String {
        if (!v.isFinite()) return "?"
        if (v == 0.0) return "0"
        val magnitude = abs(v)
        val text = when {
            magnitude >= 1e6 || magnitude < 1e-4 -> "%.2e".format(java.util.Locale.ROOT, v)
            else -> {
                val decimals = (2 - kotlin.math.floor(kotlin.math.log10(magnitude))).toInt().coerceIn(0, 6)
                "%.${decimals}f".format(java.util.Locale.ROOT, v).let { if (it.contains('.')) it.trimEnd('0').trimEnd('.') else it }
            }
        }
        return text.replace('-', '−')
    }

    /** The x in [a, b] where f is smallest, by golden-section search. */
    private fun minimise(f: (Double) -> Double, a: Double, b: Double): Double {
        val ratio = (sqrt(5.0) - 1) / 2
        var lo = a
        var hi = b
        var x1 = hi - ratio * (hi - lo)
        var x2 = lo + ratio * (hi - lo)
        var f1 = f(x1)
        var f2 = f(x2)
        repeat(80) {
            if (f1 < f2 || f2.isNaN()) {
                hi = x2
                x2 = x1
                f2 = f1
                x1 = hi - ratio * (hi - lo)
                f1 = f(x1)
            } else {
                lo = x1
                x1 = x2
                f1 = f2
                x2 = lo + ratio * (hi - lo)
                f2 = f(x2)
            }
        }
        return (lo + hi) / 2
    }
}

/** Plane geometry on (x, y) pairs. */
object Geometry2 {

    /** Where the lines through (p1, p2) and (p3, p4) meet; null when parallel. */
    fun lineLine(p1: DoubleArray, p2: DoubleArray, p3: DoubleArray, p4: DoubleArray): DoubleArray? {
        val d1x = p2[0] - p1[0]
        val d1y = p2[1] - p1[1]
        val d2x = p4[0] - p3[0]
        val d2y = p4[1] - p3[1]
        val den = d1x * d2y - d1y * d2x
        if (abs(den) < 1e-12) return null
        val t = ((p3[0] - p1[0]) * d2y - (p3[1] - p1[1]) * d2x) / den
        return doubleArrayOf(p1[0] + t * d1x, p1[1] + t * d1y)
    }

    /** Where the line through (p1, p2) meets the circle (centre c, radius r). */
    fun lineCircle(p1: DoubleArray, p2: DoubleArray, c: DoubleArray, r: Double): List<DoubleArray> {
        val dx = p2[0] - p1[0]
        val dy = p2[1] - p1[1]
        val fx = p1[0] - c[0]
        val fy = p1[1] - c[1]
        val a = dx * dx + dy * dy
        if (a < 1e-18) return emptyList()
        val b = 2 * (fx * dx + fy * dy)
        val cc = fx * fx + fy * fy - r * r
        val disc = b * b - 4 * a * cc
        if (disc < -1e-12) return emptyList()
        val root = sqrt(max(0.0, disc))
        val ts = if (root < 1e-12) listOf(-b / (2 * a)) else listOf((-b - root) / (2 * a), (-b + root) / (2 * a))
        return ts.map { t -> doubleArrayOf(p1[0] + t * dx, p1[1] + t * dy) }
    }

    /** Where two circles meet. */
    fun circleCircle(c1: DoubleArray, r1: Double, c2: DoubleArray, r2: Double): List<DoubleArray> {
        val d = hypot(c2[0] - c1[0], c2[1] - c1[1])
        if (d < 1e-12 || d > r1 + r2 + 1e-9 || d < abs(r1 - r2) - 1e-9) return emptyList()
        val a = (r1 * r1 - r2 * r2 + d * d) / (2 * d)
        val h = sqrt(max(0.0, r1 * r1 - a * a))
        val mx = c1[0] + a * (c2[0] - c1[0]) / d
        val my = c1[1] + a * (c2[1] - c1[1]) / d
        if (h < 1e-12) return listOf(doubleArrayOf(mx, my))
        val ox = -h * (c2[1] - c1[1]) / d
        val oy = h * (c2[0] - c1[0]) / d
        return listOf(doubleArrayOf(mx + ox, my + oy), doubleArrayOf(mx - ox, my - oy))
    }

    /** Centre and radius of the circle through three points; null when they are collinear. */
    fun circumcircle(a: DoubleArray, b: DoubleArray, c: DoubleArray): DoubleArray? {
        val d = 2 * (a[0] * (b[1] - c[1]) + b[0] * (c[1] - a[1]) + c[0] * (a[1] - b[1]))
        if (abs(d) < 1e-12) return null
        val a2 = a[0] * a[0] + a[1] * a[1]
        val b2 = b[0] * b[0] + b[1] * b[1]
        val c2 = c[0] * c[0] + c[1] * c[1]
        val x = (a2 * (b[1] - c[1]) + b2 * (c[1] - a[1]) + c2 * (a[1] - b[1])) / d
        val y = (a2 * (c[0] - b[0]) + b2 * (a[0] - c[0]) + c2 * (b[0] - a[0])) / d
        return doubleArrayOf(x, y, hypot(a[0] - x, a[1] - y))
    }

    /** Centre and radius of the inscribed circle; null for a degenerate triangle. */
    fun incircle(a: DoubleArray, b: DoubleArray, c: DoubleArray): DoubleArray? {
        val la = hypot(b[0] - c[0], b[1] - c[1])
        val lb = hypot(a[0] - c[0], a[1] - c[1])
        val lc = hypot(a[0] - b[0], a[1] - b[1])
        val p = la + lb + lc
        if (p < 1e-12) return null
        val x = (la * a[0] + lb * b[0] + lc * c[0]) / p
        val y = (la * a[1] + lb * b[1] + lc * c[1]) / p
        val s = p / 2
        val area = sqrt(max(0.0, s * (s - la) * (s - lb) * (s - lc)))
        if (area < 1e-12) return null
        return doubleArrayOf(x, y, area / s)
    }

    /** Area of a simple polygon given as x, y pairs (shoelace formula). */
    fun polygonArea(xy: DoubleArray): Double {
        var sum = 0.0
        val n = xy.size / 2
        for (i in 0 until n) {
            val j = (i + 1) % n
            sum += xy[2 * i] * xy[2 * j + 1] - xy[2 * j] * xy[2 * i + 1]
        }
        return abs(sum) / 2
    }

    /**
     * The part of the line from [a] towards [b] inside [view], as (x1, y1, x2, y2):
     * a segment, a ray from a, or the whole line. Null when it misses the view.
     */
    fun clip(a: DoubleArray, b: DoubleArray, kind: LineKind, view: View2): DoubleArray? {
        val dx = b[0] - a[0]
        val dy = b[1] - a[1]
        if (abs(dx) < 1e-15 && abs(dy) < 1e-15) return null
        var t0 = when (kind) {
            LineKind.LINE -> Double.NEGATIVE_INFINITY
            else -> 0.0
        }
        var t1 = when (kind) {
            LineKind.SEGMENT -> 1.0
            else -> Double.POSITIVE_INFINITY
        }
        // Liang–Barsky against the view rectangle, widened a little so ends are not cut.
        val mx = view.width * 0.02
        val my = view.height * 0.02
        val p = doubleArrayOf(-dx, dx, -dy, dy)
        val q = doubleArrayOf(a[0] - (view.xMin - mx), (view.xMax + mx) - a[0], a[1] - (view.yMin - my), (view.yMax + my) - a[1])
        for (i in 0 until 4) {
            if (abs(p[i]) < 1e-15) {
                if (q[i] < 0) return null
            } else {
                val r = q[i] / p[i]
                if (p[i] < 0) t0 = max(t0, r) else t1 = kotlin.math.min(t1, r)
            }
        }
        if (t0 > t1) return null
        return doubleArrayOf(a[0] + t0 * dx, a[1] + t0 * dy, a[0] + t1 * dx, a[1] + t1 * dy)
    }
}
