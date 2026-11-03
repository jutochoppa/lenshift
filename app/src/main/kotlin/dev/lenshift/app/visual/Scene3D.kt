package dev.lenshift.app.visual

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/** The data box a 3D scene shows. */
data class Box3(val xMin: Double, val xMax: Double, val yMin: Double, val yMax: Double, val zMin: Double, val zMax: Double) {
    fun contains(x: Double, y: Double, z: Double, slack: Double = 1e-9): Boolean {
        val sx = (xMax - xMin) * slack
        val sy = (yMax - yMin) * slack
        val sz = (zMax - zMin) * slack
        return x >= xMin - sx && x <= xMax + sx && y >= yMin - sy && y <= yMax + sy && z >= zMin - sz && z <= zMax + sz
    }
}

/**
 * Maps data coordinates into display space, where the box fits in [-1, 1]: each axis on
 * its own scale (graphs), or one scale for all (solids, so a sphere stays round).
 */
class Display3(val box: Box3, equal: Boolean) {
    private val cx = (box.xMin + box.xMax) / 2
    private val cy = (box.yMin + box.yMax) / 2
    private val cz = (box.zMin + box.zMax) / 2
    private val hx = max((box.xMax - box.xMin) / 2, 1e-9)
    private val hy = max((box.yMax - box.yMin) / 2, 1e-9)
    private val hz = max((box.zMax - box.zMin) / 2, 1e-9)
    private val longest = maxOf(hx, hy, hz)
    private val sx = if (equal) 1 / longest else 1 / hx
    private val sy = if (equal) 1 / longest else 1 / hy
    private val sz = if (equal) 1 / longest else 1 / hz

    fun x(v: Double) = ((v - cx) * sx).toFloat()
    fun y(v: Double) = ((v - cy) * sy).toFloat()
    fun z(v: Double) = ((v - cz) * sz).toFloat()
}

/** A flat polygon in display space with its normal; [bounded] solids decide the automatic box. */
class Face3(val xyz: FloatArray, val color: Int, val alpha: Float, val nx: Float, val ny: Float, val nz: Float, val edges: Boolean)

class Seg3(
    val ax: Float, val ay: Float, val az: Float,
    val bx: Float, val by: Float, val bz: Float,
    val color: Int,
    val width: Float,
    val dash: Dash = Dash.SOLID,
    val arrow: Boolean = false
)

class Dot3(val x: Float, val y: Float, val z: Float, val color: Int)

enum class LabelKind { AXIS, TICK, ITEM }

class Label3(val x: Float, val y: Float, val z: Float, val text: String, val color: Int, val kind: LabelKind)

/** Everything a 3D scene draws, in display space. */
class SceneMesh(
    val faces: List<Face3>,
    val segs: List<Seg3>,
    val dots: List<Dot3>,
    val labels: List<Label3>,
    val legend: List<LegendEntry>,
    val notes: List<String>,
    /** Data-space extent of everything with a natural size (null when nothing has one). */
    val bounds: DoubleArray?,
    /** z values of z = f(x, y) surfaces over the box, for the automatic z range. */
    val surfaceZ: DoubleArray
)

/**
 * An orbit camera around the display cube: [yaw] turns about the vertical z axis, [pitch]
 * tilts above the xy plane, [zoom] scales. The default looks from the +x, +y side and a
 * little above, so x points down-left, y right and z up.
 */
class Camera3(val yaw: Double = DEFAULT_YAW, val pitch: Double = DEFAULT_PITCH, val zoom: Double = 1.0) {
    private val rx = (-sin(yaw)).toFloat()
    private val ry = cos(yaw).toFloat()
    private val ux = (-sin(pitch) * cos(yaw)).toFloat()
    private val uy = (-sin(pitch) * sin(yaw)).toFloat()
    private val uz = cos(pitch).toFloat()
    val fx = (cos(pitch) * cos(yaw)).toFloat()
    val fy = (cos(pitch) * sin(yaw)).toFloat()
    val fz = sin(pitch).toFloat()

    /** Unit vectors of the screen's right and up directions, for lighting. */
    val right = floatArrayOf(rx, ry, 0f)
    val up = floatArrayOf(ux, uy, uz)

    fun rotated(dYaw: Double, dPitch: Double) = Camera3(yaw + dYaw, (pitch + dPitch).coerceIn(-1.5, 1.5), zoom)

    fun zoomed(factor: Double) = Camera3(yaw, pitch, (zoom * factor).coerceIn(0.35, 6.0))

    /**
     * Projects display point (x, y, z) onto a [w]×[h] canvas into out[at], out[at + 1] and
     * returns its depth (larger is nearer the viewer).
     */
    fun project(x: Float, y: Float, z: Float, w: Float, h: Float, out: FloatArray, at: Int): Float {
        val px = x * rx + y * ry
        val py = x * ux + y * uy + z * uz
        val depth = x * fx + y * fy + z * fz
        val perspective = DISTANCE / (DISTANCE - depth)
        val scale = min(w, h) * 0.5f / 1.55f * zoom.toFloat()
        out[at] = w / 2 + px * perspective * scale
        out[at + 1] = h / 2 - py * perspective * scale
        return depth
    }

    companion object {
        const val DEFAULT_YAW = 0.55
        const val DEFAULT_PITCH = 0.42
        private const val DISTANCE = 7f
    }
}

/**
 * Builds 3D scenes into flat primitives: surfaces and solids as shaded faces, curves and
 * lines as short segments (so depth sorting interleaves them with faces), points, labels,
 * axes with ticks, a grid on the floor and a faint bounding box.
 */
object Scene3DBuilder {

    private const val SOLID_ALPHA = 0.62f
    private const val PLANE_ALPHA = 0.42f
    private const val SURFACE_ALPHA = 0.93f
    private val AXIS_COLOR = 0xFFBDC1C6.toInt()
    private val GRID_COLOR = 0x33E8EAED
    private val BOX_COLOR = 0x26E8EAED

    /** Whether to use one scale on every axis: yes for solids, no for function surfaces. */
    fun equalAspect(scene: Scene3D): Boolean {
        scene.equalAspect?.let { return it }
        val solids = scene.items.any {
            it is SphereItem || it is BoxItem || it is CylinderItem || it is ConeItem || it is PolyhedronItem || it is Circle3Item
        }
        val graphs = scene.items.any { it is SurfaceItem || it is ParamSurfaceItem || it is CurveItem }
        return solids || !graphs
    }

    /** The box to start with: the spec's ranges, or what the items occupy, padded. */
    fun autoBox(scene: Scene3D, sliders: DoubleArray): Box3 {
        val probe = build(scene, sliders, Box3(-5.0, 5.0, -5.0, 5.0, -5.0, 5.0), resolution = 14, decorations = false)
        val values = probeValues(scene, sliders)
        fun spec(span: Span?) = span?.let { it.from.at(values) to it.to.at(values) }?.takeIf { (a, b) -> a.isFinite() && b.isFinite() && b > a }

        val b = probe.bounds
        val hasSurface = scene.items.any { it is SurfaceItem }
        var (xMin, xMax) = spec(scene.x) ?: b?.let { pad(it[0], it[1]) } ?: (-3.0 to 3.0)
        var (yMin, yMax) = spec(scene.y) ?: b?.let { pad(it[2], it[3]) } ?: (-3.0 to 3.0)
        if (spec(scene.x) == null && b == null && hasSurface) {
            xMin = -3.0; xMax = 3.0
        }
        if (spec(scene.y) == null && b == null && hasSurface) {
            yMin = -3.0; yMax = 3.0
        }
        val specZ = spec(scene.z)
        val (zMin, zMax) = specZ ?: run {
            var lo = b?.get(4) ?: Double.POSITIVE_INFINITY
            var hi = b?.get(5) ?: Double.NEGATIVE_INFINITY
            if (hasSurface) {
                // z over the final x, y range, judged by its middle 96%.
                val boxForZ = Box3(xMin, xMax, yMin, yMax, -1e9, 1e9)
                val zs = build(scene, sliders, boxForZ, resolution = 24, decorations = false).surfaceZ.sorted()
                if (zs.isNotEmpty()) {
                    lo = min(lo, zs[(zs.size * 0.02).toInt().coerceAtMost(zs.lastIndex)])
                    hi = max(hi, zs[(zs.size * 0.98).toInt().coerceAtMost(zs.lastIndex)])
                }
            }
            if (lo <= hi) pad(lo, hi) else -3.0 to 3.0
        }
        return square(includeZero(xMin, xMax), includeZero(yMin, yMax), includeZero(zMin, zMax), equalAspect(scene))
    }

    /** With one scale for all axes, short ranges grow to the longest so the box is a cube. */
    private fun square(x: Pair<Double, Double>, y: Pair<Double, Double>, z: Pair<Double, Double>, equal: Boolean): Box3 {
        if (!equal) return Box3(x.first, x.second, y.first, y.second, z.first, z.second)
        val half = maxOf(x.second - x.first, y.second - y.first, z.second - z.first) / 2
        fun grow(r: Pair<Double, Double>): Pair<Double, Double> {
            val c = (r.first + r.second) / 2
            return (c - half) to (c + half)
        }
        val (a, b) = grow(x)
        val (c, d) = grow(y)
        val (e, f) = grow(z)
        return Box3(a, b, c, d, e, f)
    }

    private fun pad(min: Double, max: Double): Pair<Double, Double> {
        if (max - min < 1e-9) return (min - 1) to (max + 1)
        val m = (max - min) * 0.1
        return (min - m) to (max + m)
    }

    private fun includeZero(min: Double, max: Double): Pair<Double, Double> {
        val span = max - min
        return when {
            min > 0 && min < span * 0.35 -> (-span * 0.05) to max
            max < 0 && -max < span * 0.35 -> min to (span * 0.05)
            else -> min to max
        }
    }

    private fun probeValues(scene: Scene3D, sliders: DoubleArray): DoubleArray =
        DoubleArray(scene.shared.size).also { v -> sliders.copyInto(v, 0, 0, min(sliders.size, v.size)) }

    fun build(scene: Scene3D, sliders: DoubleArray, box: Box3, resolution: Int, decorations: Boolean = true): SceneMesh =
        Builder(scene, sliders, box, resolution.coerceIn(6, 96), decorations).run()

    private class Builder(
        val scene: Scene3D,
        sliders: DoubleArray,
        val box: Box3,
        val res: Int,
        val decorations: Boolean
    ) {
        val values = DoubleArray(scene.shared.size).also { v -> sliders.copyInto(v, 0, 0, min(sliders.size, v.size)) }
        val slots = scene.shared.withIndex().associate { (i, n) -> n to i }
        val d = Display3(box, equalAspect(scene))
        val faces = mutableListOf<Face3>()
        val segs = mutableListOf<Seg3>()
        val dots = mutableListOf<Dot3>()
        val labels = mutableListOf<Label3>()
        val legend = mutableListOf<LegendEntry>()
        val notes = mutableListOf<String>()
        val surfaceZ = DoubleList()
        val points = mutableMapOf<String, DoubleArray>()
        val lines = mutableMapOf<String, Pair<DoubleArray, DoubleArray>>()
        val planes = mutableMapOf<String, DoubleArray>()
        val spheres = mutableMapOf<String, DoubleArray>()
        private var bounds: DoubleArray? = null
        private var paletteIndex = 0

        fun run(): SceneMesh {
            scene.items.forEachIndexed { i, item ->
                try {
                    item(item)
                } catch (e: IllegalArgumentException) {
                    notes += "Item ${i + 1}: ${e.message}"
                }
            }
            if (decorations) decorate()
            return SceneMesh(faces, segs, dots, labels, legend, notes.distinct(), bounds, surfaceZ.toArray())
        }

        private fun grow(x: Double, y: Double, z: Double) {
            if (!x.isFinite() || !y.isFinite() || !z.isFinite()) return
            val b = bounds ?: doubleArrayOf(x, x, y, y, z, z).also { bounds = it }
            b[0] = min(b[0], x); b[1] = max(b[1], x)
            b[2] = min(b[2], y); b[3] = max(b[3], y)
            b[4] = min(b[4], z); b[5] = max(b[5], z)
        }

        private fun color(style: Style): Int = style.color ?: VisualSpec.PALETTE[paletteIndex++ % VisualSpec.PALETTE.size]

        private fun item(item: Item3) {
            val style = item.style
            val show = style.visible
            when (item) {
                is SurfaceItem -> {
                    val c = color(style)
                    val f = item.z.bind2(values)
                    val n = res
                    val grid = Array(n + 1) { i ->
                        val x = box.xMin + (box.xMax - box.xMin) * i / n
                        DoubleArray(n + 1) { j ->
                            val y = box.yMin + (box.yMax - box.yMin) * j / n
                            f(x, y).also { z -> if (z.isFinite()) surfaceZ.add(z) }
                        }
                    }
                    if (!show) return
                    val zLo = box.zMin - (box.zMax - box.zMin) * 0.02
                    val zHi = box.zMax + (box.zMax - box.zMin) * 0.02
                    for (i in 0 until n) for (j in 0 until n) {
                        val x0 = box.xMin + (box.xMax - box.xMin) * i / n
                        val x1 = box.xMin + (box.xMax - box.xMin) * (i + 1) / n
                        val y0 = box.yMin + (box.yMax - box.yMin) * j / n
                        val y1 = box.yMin + (box.yMax - box.yMin) * (j + 1) / n
                        val zs = doubleArrayOf(grid[i][j], grid[i + 1][j], grid[i + 1][j + 1], grid[i][j + 1])
                        if (zs.any { !it.isFinite() || it < zLo || it > zHi }) continue
                        val t = ((zs.average() - box.zMin) / (box.zMax - box.zMin)).coerceIn(0.0, 1.0)
                        quad(
                            doubleArrayOf(x0, y0, zs[0], x1, y0, zs[1], x1, y1, zs[2], x0, y1, zs[3]),
                            heightTint(c, t), SURFACE_ALPHA, edges = true, bounded = false
                        )
                    }
                    style.label?.let { legend += LegendEntry(it, c, LegendKind.AREA, Dash.SOLID) }
                }
                is ParamSurfaceItem -> {
                    val c = color(style)
                    if (!show) return
                    val fx = item.x.bind2(values)
                    val fy = item.y.bind2(values)
                    val fz = item.z.bind2(values)
                    val u0 = item.u.from.at(values)
                    val u1 = item.u.to.at(values)
                    val v0 = item.v.from.at(values)
                    val v1 = item.v.to.at(values)
                    val n = res
                    val pts = Array(n + 1) { i ->
                        val u = u0 + (u1 - u0) * i / n
                        Array(n + 1) { j ->
                            val v = v0 + (v1 - v0) * j / n
                            doubleArrayOf(fx(u, v), fy(u, v), fz(u, v))
                        }
                    }
                    for (i in 0 until n) for (j in 0 until n) {
                        val q = listOf(pts[i][j], pts[i + 1][j], pts[i + 1][j + 1], pts[i][j + 1])
                        if (q.any { p -> p.any { !it.isFinite() } }) continue
                        val t = (q.sumOf { it[2] } / 4 - box.zMin) / (box.zMax - box.zMin)
                        quad(q.flatMap { it.asIterable() }.toDoubleArray(), heightTint(c, t.coerceIn(0.0, 1.0)), SURFACE_ALPHA, edges = true, bounded = true)
                    }
                    style.label?.let { legend += LegendEntry(it, c, LegendKind.AREA, Dash.SOLID) }
                }
                is CurveItem -> {
                    val c = color(style)
                    val fx = item.x.bind(values)
                    val fy = item.y.bind(values)
                    val fz = item.z.bind(values)
                    val t0 = item.t.from.at(values)
                    val t1 = item.t.to.at(values)
                    val n = 600
                    var prev: DoubleArray? = null
                    for (k in 0..n) {
                        val t = t0 + (t1 - t0) * k / n
                        val p = doubleArrayOf(fx(t), fy(t), fz(t))
                        if (p.any { !it.isFinite() }) {
                            prev = null
                            continue
                        }
                        grow(p[0], p[1], p[2])
                        if (show) prev?.let { a -> seg(a, p, c, style.width ?: 2.5f, style.dash) }
                        prev = p
                    }
                    if (show) style.label?.let { legend += LegendEntry(it, c, LegendKind.LINE, style.dash) }
                }
                is Point3Item -> {
                    val c = color(style)
                    val p = need(pos(item.at), "the point is missing")
                    item.name?.let { setPoint(it, p) }
                    grow(p[0], p[1], p[2])
                    if (!show) return
                    dots += Dot3(d.x(p[0]), d.y(p[1]), d.z(p[2]), c)
                    (style.label ?: item.name)?.let { labels += Label3(d.x(p[0]), d.y(p[1]), d.z(p[2]), it, c, LabelKind.ITEM) }
                }
                is Points3Item -> {
                    val c = color(style)
                    item.points.forEachIndexed { i, pt ->
                        val p = pos(pt) ?: return@forEachIndexed
                        grow(p[0], p[1], p[2])
                        if (!show) return@forEachIndexed
                        dots += Dot3(d.x(p[0]), d.y(p[1]), d.z(p[2]), c)
                        item.labels.getOrNull(i)?.let { labels += Label3(d.x(p[0]), d.y(p[1]), d.z(p[2]), it, c, LabelKind.ITEM) }
                    }
                    if (show) style.label?.let { legend += LegendEntry(it, c, LegendKind.DOT, Dash.SOLID) }
                }
                is Line3Item -> {
                    val c = color(style)
                    val a = need(pos(item.a), "a line needs both points")
                    val b = need(pos(item.b), "a line needs both points")
                    item.name?.let { lines[it] = a to b }
                    if (item.kind == LineKind.SEGMENT) {
                        grow(a[0], a[1], a[2])
                        grow(b[0], b[1], b[2])
                    }
                    if (!show) return
                    val clipped = when (item.kind) {
                        LineKind.SEGMENT -> a to b
                        else -> clipLine(a, b, item.kind) ?: return
                    }
                    seg(clipped.first, clipped.second, c, style.width ?: 2.5f, style.dash, pieces = 12)
                    style.label?.let { lbl ->
                        val m = mid(clipped.first, clipped.second)
                        labels += Label3(d.x(m[0]), d.y(m[1]), d.z(m[2]), lbl, c, LabelKind.ITEM)
                    }
                }
                is Vector3Item -> {
                    val c = color(style)
                    val a = need(pos(item.from), "a vector needs its start and end")
                    val b = need(pos(item.to), "a vector needs its start and end")
                    grow(a[0], a[1], a[2])
                    grow(b[0], b[1], b[2])
                    if (!show) return
                    seg(a, b, c, style.width ?: 2.8f, style.dash, pieces = 12, arrow = true)
                    style.label?.let { labels += Label3(d.x(b[0]), d.y(b[1]), d.z(b[2]), it, c, LabelKind.ITEM) }
                }
                is PlaneItem -> {
                    val c = color(style)
                    val plane = plane(item.plane) ?: throw SpecError("the plane is not defined (is its equation linear?)")
                    item.name?.let { planes[it] = plane }
                    if (!show) return
                    drawPlane(plane, c, style)
                    style.label?.let { legend += LegendEntry(it, c, LegendKind.AREA, Dash.SOLID) }
                }
                is SphereItem -> {
                    val c = color(style)
                    val center = need(pos(item.center), "sphere needs a center")
                    val r = abs(item.r.at(values))
                    item.name?.let { spheres[it] = doubleArrayOf(center[0], center[1], center[2], r) }
                    grow(center[0] - r, center[1] - r, center[2] - r)
                    grow(center[0] + r, center[1] + r, center[2] + r)
                    if (!show) return
                    val lat = max(8, res / 2)
                    val lon = max(12, res)
                    fun p(i: Int, j: Int): DoubleArray {
                        val th = PI * i / lat
                        val ph = 2 * PI * j / lon
                        return doubleArrayOf(center[0] + r * sin(th) * cos(ph), center[1] + r * sin(th) * sin(ph), center[2] + r * cos(th))
                    }
                    for (i in 0 until lat) for (j in 0 until lon) {
                        quad(doubleArrayOf(*p(i, j), *p(i + 1, j), *p(i + 1, j + 1), *p(i, j + 1)), c, solidAlpha(style), edges = false, bounded = true)
                    }
                    style.label?.let { legend += LegendEntry(it, c, LegendKind.AREA, Dash.SOLID) }
                }
                is BoxItem -> {
                    val c = color(style)
                    val lo = need(pos(item.min), "box needs min")
                    val hi = need(pos(item.max), "box needs max")
                    grow(lo[0], lo[1], lo[2])
                    grow(hi[0], hi[1], hi[2])
                    if (!show) return
                    val v = (0 until 8).map { k ->
                        doubleArrayOf(if (k and 1 == 0) lo[0] else hi[0], if (k and 2 == 0) lo[1] else hi[1], if (k and 4 == 0) lo[2] else hi[2])
                    }
                    val quads = listOf(intArrayOf(0, 1, 3, 2), intArrayOf(4, 5, 7, 6), intArrayOf(0, 1, 5, 4), intArrayOf(2, 3, 7, 6), intArrayOf(0, 2, 6, 4), intArrayOf(1, 3, 7, 5))
                    quads.forEach { q -> subdividedQuad(v[q[0]], v[q[1]], v[q[2]], v[q[3]], 3, c, solidAlpha(style)) }
                    val edges = listOf(0 to 1, 1 to 3, 3 to 2, 2 to 0, 4 to 5, 5 to 7, 7 to 6, 6 to 4, 0 to 4, 1 to 5, 2 to 6, 3 to 7)
                    edges.forEach { (a, b) -> seg(v[a], v[b], darker(c), 1.4f, Dash.SOLID, pieces = 6) }
                    style.label?.let { legend += LegendEntry(it, c, LegendKind.AREA, Dash.SOLID) }
                }
                is CylinderItem, is ConeItem -> {
                    val c = color(style)
                    val (a, b, r) = when (item) {
                        is CylinderItem -> Triple(need(pos(item.from), "cylinder needs from"), need(pos(item.to), "cylinder needs to"), abs(item.r.at(values)))
                        is ConeItem -> Triple(need(pos(item.base), "cone needs a base"), need(pos(item.apex), "cone needs an apex"), abs(item.r.at(values)))
                        else -> return
                    }
                    val axis = sub(b, a)
                    val len = norm(axis)
                    if (len < 1e-12) throw SpecError("the two ends are the same point")
                    val (e1, e2) = basis(axis)
                    val n = max(16, res)
                    fun ring(center: DoubleArray, radius: Double, k: Int): DoubleArray {
                        val t = 2 * PI * k / n
                        return doubleArrayOf(
                            center[0] + radius * (cos(t) * e1[0] + sin(t) * e2[0]),
                            center[1] + radius * (cos(t) * e1[1] + sin(t) * e2[1]),
                            center[2] + radius * (cos(t) * e1[2] + sin(t) * e2[2])
                        )
                    }
                    listOf(a, b).forEach { p -> grow(p[0] - r, p[1] - r, p[2] - r); grow(p[0] + r, p[1] + r, p[2] + r) }
                    if (!show) return
                    val cone = item is ConeItem
                    val rings = 4
                    for (k in 0 until n) for (s in 0 until rings) {
                        val c0 = lerp(a, b, s.toDouble() / rings)
                        val c1 = lerp(a, b, (s + 1).toDouble() / rings)
                        val r0 = if (cone) r * (1 - s.toDouble() / rings) else r
                        val r1 = if (cone) r * (1 - (s + 1).toDouble() / rings) else r
                        quad(doubleArrayOf(*ring(c0, r0, k), *ring(c0, r0, k + 1), *ring(c1, r1, k + 1), *ring(c1, r1, k)), c, solidAlpha(style), edges = false, bounded = true)
                    }
                    // Caps: the base, and the top of a cylinder.
                    for (center in if (cone) listOf(a) else listOf(a, b)) {
                        for (k in 0 until n) {
                            face(doubleArrayOf(*center, *ring(center, r, k), *ring(center, r, k + 1)), c, solidAlpha(style), edges = false, bounded = true)
                        }
                        for (k in 0 until n) seg(ring(center, r, k), ring(center, r, k + 1), darker(c), 1.4f, Dash.SOLID)
                    }
                    style.label?.let { legend += LegendEntry(it, c, LegendKind.AREA, Dash.SOLID) }
                }
                is PolyhedronItem -> {
                    val c = color(style)
                    val v = item.vertices.map { need(pos(it), "a vertex is missing") }
                    v.forEach { grow(it[0], it[1], it[2]) }
                    if (!show) return
                    val edges = mutableSetOf<Pair<Int, Int>>()
                    item.faces.forEach { f ->
                        face(f.flatMap { v[it].asIterable() }.toDoubleArray(), c, solidAlpha(style), edges = false, bounded = true)
                        for (k in f.indices) {
                            val a = f[k]
                            val b = f[(k + 1) % f.size]
                            edges += min(a, b) to max(a, b)
                        }
                    }
                    edges.forEach { (a, b) -> seg(v[a], v[b], darker(c), 1.4f, Dash.SOLID, pieces = 6) }
                    style.label?.let { legend += LegendEntry(it, c, LegendKind.AREA, Dash.SOLID) }
                }
                is Polygon3Item -> {
                    val c = color(style)
                    val v = item.points.map { need(pos(it), "a polygon point is missing") }
                    v.forEach { grow(it[0], it[1], it[2]) }
                    if (!show) return
                    face(v.flatMap { it.asIterable() }.toDoubleArray(), c, solidAlpha(style), edges = false, bounded = true)
                    for (k in v.indices) seg(v[k], v[(k + 1) % v.size], c, 2f, style.dash, pieces = 6)
                    style.label?.let { legend += LegendEntry(it, c, LegendKind.AREA, Dash.SOLID) }
                }
                is Circle3Item -> {
                    val c = color(style)
                    val center = need(pos(item.center), "circle needs a center")
                    val normal = need(pos(item.normal), "circle needs a normal")
                    val r = abs(item.r.at(values))
                    grow(center[0] - r, center[1] - r, center[2] - r)
                    grow(center[0] + r, center[1] + r, center[2] + r)
                    if (show) circle(center, normal, r, c, style)
                }
                is Intersection3Item -> {
                    val c = color(style)
                    if (show) intersection(item.a, item.b, c, style)
                }
                is Text3Item -> {
                    val p = need(pos(item.at), "text needs a position")
                    grow(p[0], p[1], p[2])
                    if (show) labels += Label3(d.x(p[0]), d.y(p[1]), d.z(p[2]), item.text, style.color ?: 0xFFE8EAED.toInt(), LabelKind.ITEM)
                }
            }
        }

        // ----------------------------------------------------------- positions

        private fun setPoint(name: String, p: DoubleArray) {
            points[name] = p
            slots["$name.x"]?.let { values[it] = p[0] }
            slots["$name.y"]?.let { values[it] = p[1] }
            slots["$name.z"]?.let { values[it] = p[2] }
        }

        fun pos(p: Point3): DoubleArray? = when (p) {
            is AtXYZ -> doubleArrayOf(p.x.at(values), p.y.at(values), p.z.at(values)).takeIf { a -> a.all { it.isFinite() } }
            is Point3Ref -> points[p.name]
            is Midpoint3 -> {
                val a = pos(p.a)
                val b = pos(p.b)
                if (a == null || b == null) null else lerp(a, b, 0.5)
            }
        }

        /** (a, b, c, d) with a·x + b·y + c·z = d, or null when undefined. */
        private fun plane(def: PlaneDef): DoubleArray? {
            val out = when (def) {
                is PlaneEquation -> {
                    val g = def.f.bind3(values)
                    val f0 = g(0.0, 0.0, 0.0)
                    val a = g(1.0, 0.0, 0.0) - f0
                    val b = g(0.0, 1.0, 0.0) - f0
                    val c = g(0.0, 0.0, 1.0) - f0
                    val probe = g(0.7, -1.3, 2.1)
                    if (abs(probe - (f0 + 0.7 * a - 1.3 * b + 2.1 * c)) > 1e-6 * (1 + abs(probe))) return null
                    doubleArrayOf(a, b, c, -f0)
                }
                is PlanePointNormal -> {
                    val n = pos(def.normal) ?: return null
                    val dd = def.point?.let { pos(it) }?.let { dot(n, it) } ?: def.d?.at(values) ?: 0.0
                    doubleArrayOf(n[0], n[1], n[2], dd)
                }
                is PlaneThroughPoints -> {
                    val a = pos(def.a) ?: return null
                    val b = pos(def.b) ?: return null
                    val c = pos(def.c) ?: return null
                    val n = cross(sub(b, a), sub(c, a))
                    doubleArrayOf(n[0], n[1], n[2], dot(n, a))
                }
            }
            return out.takeIf { o -> o.all { it.isFinite() } && norm(doubleArrayOf(o[0], o[1], o[2])) > 1e-12 }
        }

        // -------------------------------------------------------------- drawing

        private fun drawPlane(plane: DoubleArray, c: Int, style: Style) {
            val n = doubleArrayOf(plane[0], plane[1], plane[2])
            val nn = dot(n, n)
            val origin = doubleArrayOf(n[0] * plane[3] / nn, n[1] * plane[3] / nn, n[2] * plane[3] / nn)
            val (e1, e2) = basis(n)
            val extent = 2 * sqrt((box.xMax - box.xMin).let { it * it } + (box.yMax - box.yMin).let { it * it } + (box.zMax - box.zMin).let { it * it }) +
                norm(sub(origin, doubleArrayOf((box.xMin + box.xMax) / 2, (box.yMin + box.yMax) / 2, (box.zMin + box.zMax) / 2)))
            fun at(s: Double, t: Double) = doubleArrayOf(
                origin[0] + s * e1[0] + t * e2[0], origin[1] + s * e1[1] + t * e2[1], origin[2] + s * e1[2] + t * e2[2]
            )
            // The outline: a big square on the plane clipped to the box.
            val outline = clipToBox(listOf(at(-extent, -extent), at(extent, -extent), at(extent, extent), at(-extent, extent)))
            if (outline.size < 3) return
            for (k in outline.indices) seg(outline[k], outline[(k + 1) % outline.size], c, 1.6f, style.dash, pieces = 4)
            // The fill, in small pieces so depth sorting works against other surfaces.
            val cells = 10
            for (i in 0 until cells) for (j in 0 until cells) {
                val s0 = -extent + 2 * extent * i / cells
                val s1 = -extent + 2 * extent * (i + 1) / cells
                val t0 = -extent + 2 * extent * j / cells
                val t1 = -extent + 2 * extent * (j + 1) / cells
                val piece = clipToBox(listOf(at(s0, t0), at(s1, t0), at(s1, t1), at(s0, t1)))
                if (piece.size >= 3) face(piece.flatMap { it.asIterable() }.toDoubleArray(), c, style.fill?.let { ((it ushr 24) / 255f).takeIf { a -> a > 0 } } ?: PLANE_ALPHA, edges = false, bounded = false)
            }
        }

        private fun circle(center: DoubleArray, normal: DoubleArray, r: Double, c: Int, style: Style) {
            if (norm(normal) < 1e-12) throw SpecError("the circle's normal is zero")
            val (e1, e2) = basis(normal)
            val n = 72
            fun p(k: Int): DoubleArray {
                val t = 2 * PI * k / n
                return doubleArrayOf(
                    center[0] + r * (cos(t) * e1[0] + sin(t) * e2[0]),
                    center[1] + r * (cos(t) * e1[1] + sin(t) * e2[1]),
                    center[2] + r * (cos(t) * e1[2] + sin(t) * e2[2])
                )
            }
            for (k in 0 until n) seg(p(k), p(k + 1), c, style.width ?: 2.5f, style.dash)
            style.fill?.let { fill ->
                val alpha = ((fill ushr 24) / 255f).takeIf { it > 0 } ?: PLANE_ALPHA
                for (k in 0 until n) face(doubleArrayOf(*center, *p(k), *p(k + 1)), c, alpha, edges = false, bounded = true)
            }
            style.label?.let { labels += Label3(d.x(p(n / 8)[0]), d.y(p(n / 8)[1]), d.z(p(n / 8)[2]), it, c, LabelKind.ITEM) }
        }

        /** Where two named planes, spheres or lines meet. */
        private fun intersection(a: String, b: String, c: Int, style: Style) {
            val pa = planes[a]
            val pb = planes[b]
            val sa = spheres[a]
            val sb = spheres[b]
            val la = lines[a]
            val lb = lines[b]
            when {
                pa != null && pb != null -> {
                    val n1 = doubleArrayOf(pa[0], pa[1], pa[2])
                    val n2 = doubleArrayOf(pb[0], pb[1], pb[2])
                    val dir = cross(n1, n2)
                    if (norm(dir) < 1e-12) throw SpecError("the planes are parallel")
                    // A point on both planes: the combination of the normals that satisfies both.
                    val n1n1 = dot(n1, n1)
                    val n2n2 = dot(n2, n2)
                    val n1n2 = dot(n1, n2)
                    val det = n1n1 * n2n2 - n1n2 * n1n2
                    val c1 = (pa[3] * n2n2 - pb[3] * n1n2) / det
                    val c2 = (pb[3] * n1n1 - pa[3] * n1n2) / det
                    val p = doubleArrayOf(c1 * n1[0] + c2 * n2[0], c1 * n1[1] + c2 * n2[1], c1 * n1[2] + c2 * n2[2])
                    val clipped = clipLine(p, add(p, dir), LineKind.LINE) ?: return
                    seg(clipped.first, clipped.second, c, style.width ?: 3f, style.dash, pieces = 16)
                }
                (pa != null && sb != null) || (sa != null && pb != null) -> {
                    val plane = pa ?: pb!!
                    val sphere = sb ?: sa!!
                    val n = doubleArrayOf(plane[0], plane[1], plane[2])
                    val len = norm(n)
                    val centre = doubleArrayOf(sphere[0], sphere[1], sphere[2])
                    val dist = (dot(n, centre) - plane[3]) / len
                    if (abs(dist) > sphere[3]) throw SpecError("the plane misses the sphere")
                    val foot = doubleArrayOf(centre[0] - n[0] / len * dist, centre[1] - n[1] / len * dist, centre[2] - n[2] / len * dist)
                    circle(foot, n, sqrt(sphere[3] * sphere[3] - dist * dist), c, style)
                }
                (pa != null && lb != null) || (la != null && pb != null) -> {
                    val plane = pa ?: pb!!
                    val line = lb ?: la!!
                    val n = doubleArrayOf(plane[0], plane[1], plane[2])
                    val dir = sub(line.second, line.first)
                    val den = dot(n, dir)
                    if (abs(den) < 1e-12) throw SpecError("the line is parallel to the plane")
                    val t = (plane[3] - dot(n, line.first)) / den
                    val p = add(line.first, scale(dir, t))
                    dots += Dot3(d.x(p[0]), d.y(p[1]), d.z(p[2]), c)
                    style.label?.let { labels += Label3(d.x(p[0]), d.y(p[1]), d.z(p[2]), it, c, LabelKind.ITEM) }
                }
                (sa != null && lb != null) || (la != null && sb != null) -> {
                    val sphere = sa ?: sb!!
                    val line = lb ?: la!!
                    val centre = doubleArrayOf(sphere[0], sphere[1], sphere[2])
                    val dir = sub(line.second, line.first)
                    val f = sub(line.first, centre)
                    val qa = dot(dir, dir)
                    val qb = 2 * dot(f, dir)
                    val qc = dot(f, f) - sphere[3] * sphere[3]
                    val disc = qb * qb - 4 * qa * qc
                    if (disc < 0) throw SpecError("the line misses the sphere")
                    for (t in listOf((-qb - sqrt(disc)) / (2 * qa), (-qb + sqrt(disc)) / (2 * qa))) {
                        val p = add(line.first, scale(dir, t))
                        dots += Dot3(d.x(p[0]), d.y(p[1]), d.z(p[2]), c)
                    }
                }
                sa != null && sb != null -> {
                    val c1 = doubleArrayOf(sa[0], sa[1], sa[2])
                    val c2 = doubleArrayOf(sb[0], sb[1], sb[2])
                    val axis = sub(c2, c1)
                    val dd = norm(axis)
                    if (dd < 1e-12 || dd > sa[3] + sb[3] || dd < abs(sa[3] - sb[3])) throw SpecError("the spheres don't meet in a circle")
                    val a1 = (sa[3] * sa[3] - sb[3] * sb[3] + dd * dd) / (2 * dd)
                    val centre = add(c1, scale(axis, a1 / dd))
                    circle(centre, axis, sqrt(max(0.0, sa[3] * sa[3] - a1 * a1)), c, style)
                }
                la != null && lb != null -> {
                    val d1 = sub(la.second, la.first)
                    val d2 = sub(lb.second, lb.first)
                    val w = sub(la.first, lb.first)
                    val aa = dot(d1, d1)
                    val bb = dot(d1, d2)
                    val cc = dot(d2, d2)
                    val dd = dot(d1, w)
                    val ee = dot(d2, w)
                    val den = aa * cc - bb * bb
                    if (abs(den) < 1e-12) throw SpecError("the lines are parallel")
                    val s = (bb * ee - cc * dd) / den
                    val t = (aa * ee - bb * dd) / den
                    val p = add(la.first, scale(d1, s))
                    val q = add(lb.first, scale(d2, t))
                    if (norm(sub(p, q)) > 1e-6 * (1 + norm(p))) throw SpecError("the lines don't meet")
                    dots += Dot3(d.x(p[0]), d.y(p[1]), d.z(p[2]), c)
                    style.label?.let { labels += Label3(d.x(p[0]), d.y(p[1]), d.z(p[2]), it, c, LabelKind.ITEM) }
                }
                else -> throw SpecError("'$a' and '$b' must be named planes, spheres or lines defined earlier")
            }
        }

        /** Axes with arrows, ticks and names, the origin, a floor grid and the box outline. */
        private fun decorate() {
            val ox = if (0.0 in box.xMin..box.xMax) 0.0 else box.xMin
            val oy = if (0.0 in box.yMin..box.yMax) 0.0 else box.yMin
            val oz = if (0.0 in box.zMin..box.zMax) 0.0 else box.zMin
            if (scene.grid) {
                val stepX = Plot2D.niceStep(box.xMax - box.xMin, 8)
                val stepY = Plot2D.niceStep(box.yMax - box.yMin, 8)
                Plot2D.ticks(box.xMin, box.xMax, stepX).forEach { x ->
                    seg(doubleArrayOf(x, box.yMin, oz), doubleArrayOf(x, box.yMax, oz), GRID_COLOR, 1f, Dash.SOLID, pieces = 8)
                }
                Plot2D.ticks(box.yMin, box.yMax, stepY).forEach { y ->
                    seg(doubleArrayOf(box.xMin, y, oz), doubleArrayOf(box.xMax, y, oz), GRID_COLOR, 1f, Dash.SOLID, pieces = 8)
                }
            }
            // The box outline.
            val corners = (0 until 8).map { k ->
                doubleArrayOf(if (k and 1 == 0) box.xMin else box.xMax, if (k and 2 == 0) box.yMin else box.yMax, if (k and 4 == 0) box.zMin else box.zMax)
            }
            listOf(0 to 1, 1 to 3, 3 to 2, 2 to 0, 4 to 5, 5 to 7, 7 to 6, 6 to 4, 0 to 4, 1 to 5, 2 to 6, 3 to 7)
                .forEach { (a, b) -> seg(corners[a], corners[b], BOX_COLOR, 1f, Dash.SOLID, pieces = 4) }
            if (!scene.axes) return
            val names = listOf("x", "y", "z")
            for (axis in 0..2) {
                val lo = when (axis) { 0 -> box.xMin; 1 -> box.yMin; else -> box.zMin }
                val hi = when (axis) { 0 -> box.xMax; 1 -> box.yMax; else -> box.zMax }
                fun at(v: Double) = doubleArrayOf(if (axis == 0) v else ox, if (axis == 1) v else oy, if (axis == 2) v else oz)
                seg(at(lo), at(hi), AXIS_COLOR, 1.8f, Dash.SOLID, pieces = 16, arrow = true)
                val end = at(hi + (hi - lo) * 0.06)
                labels += Label3(d.x(end[0]), d.y(end[1]), d.z(end[2]), names[axis], AXIS_COLOR, LabelKind.AXIS)
                val step = Plot2D.niceStep(hi - lo, 5)
                Plot2D.ticks(lo, hi, step).forEach { v ->
                    if (abs(v - (when (axis) { 0 -> ox; 1 -> oy; else -> oz })) < step * 1e-6) return@forEach
                    val p = at(v)
                    labels += Label3(d.x(p[0]), d.y(p[1]), d.z(p[2]), Plot2D.formatTick(v, step), AXIS_COLOR, LabelKind.TICK)
                }
            }
            if (box.contains(0.0, 0.0, 0.0)) {
                dots += Dot3(d.x(0.0), d.y(0.0), d.z(0.0), AXIS_COLOR)
                labels += Label3(d.x(0.0), d.y(0.0), d.z(0.0), "O", AXIS_COLOR, LabelKind.TICK)
            }
        }

        // ------------------------------------------------------------ primitives

        /** Adds a polygon given in data space; computes its display-space normal. */
        private fun face(xyz: DoubleArray, color: Int, alpha: Float, edges: Boolean, bounded: Boolean) {
            val n = xyz.size / 3
            if (n < 3) return
            val out = FloatArray(xyz.size)
            for (k in 0 until n) {
                out[3 * k] = d.x(xyz[3 * k])
                out[3 * k + 1] = d.y(xyz[3 * k + 1])
                out[3 * k + 2] = d.z(xyz[3 * k + 2])
                if (bounded) grow(xyz[3 * k], xyz[3 * k + 1], xyz[3 * k + 2])
            }
            // Newell's method: a stable normal for any planar polygon.
            var nx = 0f
            var ny = 0f
            var nz = 0f
            for (k in 0 until n) {
                val j = (k + 1) % n
                nx += (out[3 * k + 1] - out[3 * j + 1]) * (out[3 * k + 2] + out[3 * j + 2])
                ny += (out[3 * k + 2] - out[3 * j + 2]) * (out[3 * k] + out[3 * j])
                nz += (out[3 * k] - out[3 * j]) * (out[3 * k + 1] + out[3 * j + 1])
            }
            val len = sqrt(nx * nx + ny * ny + nz * nz)
            if (len < 1e-12f) return
            faces += Face3(out, color, alpha, nx / len, ny / len, nz / len, edges)
        }

        private fun quad(xyz: DoubleArray, color: Int, alpha: Float, edges: Boolean, bounded: Boolean) =
            face(xyz, color, alpha, edges, bounded)

        private fun subdividedQuad(a: DoubleArray, b: DoubleArray, c: DoubleArray, dd: DoubleArray, n: Int, color: Int, alpha: Float) {
            for (i in 0 until n) for (j in 0 until n) {
                fun p(s: Double, t: Double): DoubleArray {
                    val ab = lerp(a, b, s)
                    val dc = lerp(dd, c, s)
                    return lerp(ab, dc, t)
                }
                val s0 = i.toDouble() / n
                val s1 = (i + 1).toDouble() / n
                val t0 = j.toDouble() / n
                val t1 = (j + 1).toDouble() / n
                face(doubleArrayOf(*p(s0, t0), *p(s1, t0), *p(s1, t1), *p(s0, t1)), color, alpha, edges = false, bounded = true)
            }
        }

        /** A segment in data space, in [pieces] parts so it sorts well against faces. */
        private fun seg(a: DoubleArray, b: DoubleArray, color: Int, width: Float, dash: Dash, pieces: Int = 1, arrow: Boolean = false) {
            for (k in 0 until pieces) {
                val p = lerp(a, b, k.toDouble() / pieces)
                val q = lerp(a, b, (k + 1).toDouble() / pieces)
                segs += Seg3(d.x(p[0]), d.y(p[1]), d.z(p[2]), d.x(q[0]), d.y(q[1]), d.z(q[2]), color, width, dash, arrow && k == pieces - 1)
            }
        }

        /** The part of the line (or ray) from [a] through [b] inside the box. */
        private fun clipLine(a: DoubleArray, b: DoubleArray, kind: LineKind): Pair<DoubleArray, DoubleArray>? {
            val dir = sub(b, a)
            var t0 = if (kind == LineKind.LINE) Double.NEGATIVE_INFINITY else 0.0
            var t1 = if (kind == LineKind.SEGMENT) 1.0 else Double.POSITIVE_INFINITY
            val lo = doubleArrayOf(box.xMin, box.yMin, box.zMin)
            val hi = doubleArrayOf(box.xMax, box.yMax, box.zMax)
            for (k in 0..2) {
                if (abs(dir[k]) < 1e-15) {
                    if (a[k] < lo[k] || a[k] > hi[k]) return null
                } else {
                    var ta = (lo[k] - a[k]) / dir[k]
                    var tb = (hi[k] - a[k]) / dir[k]
                    if (ta > tb) ta = tb.also { tb = ta }
                    t0 = max(t0, ta)
                    t1 = min(t1, tb)
                }
            }
            if (t0 > t1) return null
            return add(a, scale(dir, t0)) to add(a, scale(dir, t1))
        }

        /** Sutherland–Hodgman clipping of a convex polygon against the six faces of the box. */
        private fun clipToBox(polygon: List<DoubleArray>): List<DoubleArray> {
            var poly = polygon
            val planes = listOf(
                Triple(0, box.xMin, true), Triple(0, box.xMax, false),
                Triple(1, box.yMin, true), Triple(1, box.yMax, false),
                Triple(2, box.zMin, true), Triple(2, box.zMax, false)
            )
            for ((axis, limit, keepAbove) in planes) {
                if (poly.isEmpty()) break
                val next = mutableListOf<DoubleArray>()
                fun inside(p: DoubleArray) = if (keepAbove) p[axis] >= limit else p[axis] <= limit
                for (k in poly.indices) {
                    val cur = poly[k]
                    val prev = poly[(k + poly.size - 1) % poly.size]
                    val curIn = inside(cur)
                    val prevIn = inside(prev)
                    if (curIn != prevIn) {
                        val t = (limit - prev[axis]) / (cur[axis] - prev[axis])
                        next += lerp(prev, cur, t)
                    }
                    if (curIn) next += cur
                }
                poly = next
            }
            return poly
        }

        private fun solidAlpha(style: Style): Float = style.fill?.let { ((it ushr 24) / 255f).takeIf { a -> a > 0 } } ?: SOLID_ALPHA

        /** Lighter towards the top of the box, so height reads at a glance. */
        private fun heightTint(color: Int, t: Double): Int {
            val f = (0.72 + 0.4 * t).toFloat()
            fun ch(shift: Int) = (((color shr shift) and 0xFF) * f).roundToInt().coerceIn(0, 255)
            return (color and 0xFF000000.toInt()) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
        }

        private fun darker(color: Int): Int {
            fun ch(shift: Int) = (((color shr shift) and 0xFF) * 0.7f).roundToInt()
            return (0xFF shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
        }

        private fun <T> need(value: T?, message: String): T = value ?: throw SpecError(message)
    }

    // ------------------------------------------------------------------ vectors

    private fun sub(a: DoubleArray, b: DoubleArray) = doubleArrayOf(a[0] - b[0], a[1] - b[1], a[2] - b[2])
    private fun add(a: DoubleArray, b: DoubleArray) = doubleArrayOf(a[0] + b[0], a[1] + b[1], a[2] + b[2])
    private fun scale(a: DoubleArray, s: Double) = doubleArrayOf(a[0] * s, a[1] * s, a[2] * s)
    private fun dot(a: DoubleArray, b: DoubleArray) = a[0] * b[0] + a[1] * b[1] + a[2] * b[2]
    private fun norm(a: DoubleArray) = sqrt(dot(a, a))
    private fun mid(a: DoubleArray, b: DoubleArray) = lerp(a, b, 0.5)
    private fun lerp(a: DoubleArray, b: DoubleArray, t: Double) =
        doubleArrayOf(a[0] + (b[0] - a[0]) * t, a[1] + (b[1] - a[1]) * t, a[2] + (b[2] - a[2]) * t)

    private fun cross(a: DoubleArray, b: DoubleArray) =
        doubleArrayOf(a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0])

    /** Two unit vectors perpendicular to [n] and to each other. */
    private fun basis(n: DoubleArray): Pair<DoubleArray, DoubleArray> {
        val len = norm(n)
        val u = doubleArrayOf(n[0] / len, n[1] / len, n[2] / len)
        val helper = if (abs(u[0]) < 0.9) doubleArrayOf(1.0, 0.0, 0.0) else doubleArrayOf(0.0, 1.0, 0.0)
        val e1 = cross(u, helper).let { c -> scale(c, 1 / norm(c)) }
        val e2 = cross(u, e1)
        return e1 to e2
    }
}
