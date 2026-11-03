package dev.lenshift.app.visual

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.tan

/** The numeric engine behind graphs: sampling, analysis, constructions and 3D. */
class EngineTest {

    private val view = View2(-5.0, 5.0, -5.0, 5.0)

    // --------------------------------------------------------------- sampling

    @Test
    fun `a smooth function is one line and a pole splits it`() {
        val parabola = Plot2D.sampleFunction({ it * it }, -2.0, 2.0, view, 400)
        assertEquals(1, parabola.size)
        assertEquals(-2.0, parabola[0][0], 1e-12)
        assertEquals(4.0, parabola[0][1], 1e-12)

        val tangent = Plot2D.sampleFunction(::tan, -PI, PI, view, 400)
        assertEquals("tan has two poles in (-π, π)", 3, tangent.size)

        val reciprocal = Plot2D.sampleFunction({ 1 / it }, -5.0, 5.0, view, 400)
        assertEquals(2, reciprocal.size)
    }

    @Test
    fun `a curve starts where its domain does`() {
        val root = Plot2D.sampleFunction({ kotlin.math.sqrt(it) }, -1.0, 4.0, view, 400)
        assertEquals(1, root.size)
        assertTrue("starts near 0 but was ${root[0][0]}", root[0][0] in -1e-6..0.02)
    }

    @Test
    fun `implicit circles and regions`() {
        val segments = Plot2D.contour({ x, y -> x * x + y * y - 4 }, view, 80, 80)
        assertTrue(segments.size > 40)
        for (k in segments.indices step 2) {
            assertEquals(2.0, hypot(segments[k], segments[k + 1]), 0.05)
        }
        val cells = Plot2D.region({ x, y -> y - x }, Relation.GT, view, 10, 10)
        assertEquals(45, cells.count { it })
    }

    @Test
    fun `ticks are round numbers`() {
        assertEquals(2.0, Plot2D.niceStep(17.0, 10), 1e-12)
        assertEquals(0.5, Plot2D.niceStep(4.0, 10), 1e-12)
        assertEquals(listOf(-4.0, -2.0, 0.0, 2.0, 4.0), Plot2D.ticks(-5.0, 5.0, 2.0))
        assertEquals("−1.5", Plot2D.formatTick(-1.5, 0.5))
        assertEquals("10", Plot2D.formatTick(10.0, 5.0))
    }

    // ---------------------------------------------------------------- analysis

    @Test
    fun `roots, extrema and integrals`() {
        assertEquals(listOf(-1.0, 1.0), Analysis.roots({ it * it - 1 }, -3.0, 3.0).map { Math.round(it * 1e6) / 1e6 })
        val touching = Analysis.roots({ (it - 1) * (it - 1) }, -3.0, 3.0)
        assertEquals(1, touching.size)
        assertEquals(1.0, touching[0], 1e-4)
        assertTrue("a pole is not a root", Analysis.roots({ 1 / it }, -3.0, 3.0).isEmpty())

        val peaks = Analysis.extrema(::sin, 0.0, 2 * PI)
        assertEquals(2, peaks.size)
        assertEquals(PI / 2, peaks[0].first, 1e-6)
        assertTrue(peaks[0].second)
        assertFalse(peaks[1].second)

        assertEquals(9.0, Analysis.integral({ it * it }, 0.0, 3.0), 1e-9)
        val (left, pieces) = Analysis.riemann({ it }, 0.0, 1.0, 4, RiemannType.LEFT)
        assertEquals(0.375, left, 1e-12)
        assertEquals(4, pieces.size)
    }

    @Test
    fun `fits recover exact data`() {
        val xs = doubleArrayOf(0.0, 1.0, 2.0, 3.0)
        val linear = Analysis.fit(xs, xs.map { 2 * it + 1 }.toDoubleArray(), FitType.LINEAR)!!
        assertEquals("y = 2x + 1", linear.equation)
        assertEquals(1.0, linear.r2, 1e-12)
        val quadratic = Analysis.fit(xs, xs.map { it * it - 3 }.toDoubleArray(), FitType.QUADRATIC)!!
        assertEquals(6.0, quadratic.f(3.0), 1e-9)
        val exponential = Analysis.fit(xs, xs.map { 3 * kotlin.math.exp(0.5 * it) }.toDoubleArray(), FitType.EXPONENTIAL)!!
        assertEquals(3 * kotlin.math.exp(2.0), exponential.f(4.0), 1e-6)
        assertEquals(null, Analysis.fit(xs, doubleArrayOf(1.0, -1.0, 2.0, 3.0), FitType.EXPONENTIAL))
        assertEquals("−0.00123", Analysis.num(-0.00123))
        assertEquals("1.23e+06", Analysis.num(1_234_567.0))
    }

    @Test
    fun `plane geometry`() {
        val a = doubleArrayOf(0.0, 0.0)
        val b = doubleArrayOf(4.0, 0.0)
        val c = doubleArrayOf(0.0, 3.0)
        val cc = Geometry2.circumcircle(a, b, c)!!
        assertEquals(2.0, cc[0], 1e-12)
        assertEquals(1.5, cc[1], 1e-12)
        assertEquals(2.5, cc[2], 1e-12)
        assertEquals(1.0, Geometry2.incircle(a, b, c)!![2], 1e-12)
        assertEquals(2, Geometry2.circleCircle(a, 2.0, b, 3.0).size)
        assertEquals(6.0, Geometry2.polygonArea(doubleArrayOf(0.0, 0.0, 4.0, 0.0, 0.0, 3.0)), 1e-12)
        val clipped = Geometry2.clip(doubleArrayOf(0.0, 0.0), doubleArrayOf(1.0, 1.0), LineKind.LINE, view)!!
        assertTrue(clipped[0] < -4.9 && clipped[2] > 4.9)
    }

    // ----------------------------------------------------------- constructions

    private fun resolve(json: String, drags: Map<String, DoubleArray> = emptyMap()): Scene2D {
        val g = VisualSpec.parse(json, threeD = false) as Graph2D
        return Resolve2D.resolve(g, g.params.map { it.value }.toDoubleArray(), drags, view, 800f, 800f)
    }

    @Test
    fun `constructions follow their points`() {
        val spec = """{"items": [
            {"point": [0, 0], "name": "A", "drag": true},
            {"point": [4, 0], "name": "B"},
            {"point": {"midpoint": ["A", "B"]}, "name": "M"},
            {"segment": ["A", "B"], "name": "ab"},
            {"perpendicular": {"to": "ab", "through": "M"}, "name": "pm"},
            {"circle": {"center": "M", "through": "A"}, "name": "c"},
            {"point": {"intersection": ["pm", "c"], "index": 0}, "name": "T"}
        ]}"""
        val scene = resolve(spec)
        val m = scene.dots.first { it.label == "M" }
        assertEquals(2.0, m.x, 1e-12)
        val t = scene.dots.first { it.label == "T" }
        assertEquals(2.0, hypot(t.x - 2, t.y), 1e-9)
        assertTrue(scene.notes.isEmpty())

        // Dragging A moves the midpoint with it.
        val moved = resolve(spec, mapOf("A" to doubleArrayOf(-4.0, 2.0)))
        val m2 = moved.dots.first { it.label == "M" }
        assertEquals(0.0, m2.x, 1e-12)
        assertEquals(1.0, m2.y, 1e-12)
        assertTrue(moved.dots.first { it.label == "A" }.drag is FreeDrag)
    }

    @Test
    fun `gliders, tangents and coordinates in expressions`() {
        val scene = resolve(
            """{"items": [
              {"fn": "x^2", "name": "f"},
              {"point": {"on": "f", "x": 1}, "name": "P", "drag": true},
              {"tangent": {"fn": "f", "at": "P"}},
              {"point": ["P.x", "f'(P.x)"], "name": "S"}
            ]}""",
            mapOf("P" to doubleArrayOf(2.0))
        )
        val p = scene.dots.first { it.label == "P" }
        assertEquals(4.0, p.y, 1e-12)
        assertTrue(p.drag is AlongGraph)
        assertEquals(4.0, scene.dots.first { it.label == "S" }.y, 1e-4)
        assertTrue(scene.legend.any { it.label.contains("slope 4") })
    }

    @Test
    fun `analysis items mark points and values`() {
        val scene = resolve(
            """{"items": [
              {"roots": "x^2 - 1"},
              {"area": {"fn": "x^2", "x": [0, 2]}},
              {"angle": {"vertex": [0,0], "from": [3,0], "to": [0,4]}}
            ]}"""
        )
        assertEquals(setOf("(−1, 0)", "(1, 0)"), scene.dots.mapNotNull { it.label }.toSet())
        assertTrue(scene.texts.any { it.text == "∫ = 2.67" })
        assertTrue(scene.angles.single().right)
        assertEquals("90°", scene.angles.single().label)
    }

    @Test
    fun `the automatic view frames what is drawn`() {
        val g = VisualSpec.parse("""{"items": [{"point": [10, 20]}, {"point": [14, 25]}]}""", threeD = false) as Graph2D
        val scene = Resolve2D.resolve(g, DoubleArray(0), emptyMap(), View2(-10.0, 10.0, -10.0, 10.0), 800f, 600f)
        val v = Plot2D.autoView(g, scene)
        assertTrue(v.xMin < 10 && v.xMax > 14 && v.yMin < 20 && v.yMax > 25)

        val parabola = VisualSpec.parse("""{"fn": "x^2"}""", threeD = false) as Graph2D
        val pv = Plot2D.autoView(parabola, Resolve2D.resolve(parabola, DoubleArray(0), emptyMap(), View2(-10.0, 10.0, -10.0, 10.0), 800f, 600f))
        assertEquals(-10.0, pv.xMin, 1e-9)
        assertTrue(pv.yMin < 0.5 && pv.yMax > 90)
        assertFalse(Plot2D.wantsEqualAspect(parabola))
        assertTrue(Plot2D.wantsEqualAspect(VisualSpec.parse("""{"circle": {"center": [0,0], "r": 1}}""", threeD = false) as Graph2D))
    }

    // ---------------------------------------------------------------------- 3D

    @Test
    fun `the default camera shows x down-left, y right and z up`() {
        val cam = Camera3()
        val out = FloatArray(2)
        val w = 1000f
        val h = 1000f
        cam.project(0f, 0f, 0f, w, h, out, 0)
        val cx = out[0]
        val cy = out[1]
        cam.project(1f, 0f, 0f, w, h, out, 0)
        assertTrue(out[0] < cx && out[1] > cy)
        cam.project(0f, 1f, 0f, w, h, out, 0)
        assertTrue(out[0] > cx)
        cam.project(0f, 0f, 1f, w, h, out, 0)
        assertTrue(out[1] < cy)
        assertEquals("tilting stops short of straight down", 1.5, cam.rotated(0.0, 5.0).pitch, 1e-12)
    }

    @Test
    fun `scenes build faces, axes and intersections`() {
        val s = VisualSpec.parse(
            """{"items": [
              {"sphere": {"center": [0,0,0], "r": 1}, "name": "s"},
              {"plane": "z = 0.5", "name": "p"},
              {"intersection": ["p", "s"]},
              {"point": [1, 1, 1], "name": "P"}
            ]}""",
            threeD = true
        ) as Scene3D
        val box = Scene3DBuilder.autoBox(s, DoubleArray(0))
        assertTrue(box.contains(1.0, 1.0, 1.0))
        assertTrue(Scene3DBuilder.equalAspect(s))
        val mesh = Scene3DBuilder.build(s, DoubleArray(0), box, resolution = 24)
        assertTrue(mesh.notes.toString(), mesh.notes.isEmpty())
        assertTrue(mesh.faces.size > 100)
        assertTrue(mesh.labels.any { it.text == "x" } && mesh.labels.any { it.text == "z" })
        assertTrue(mesh.labels.any { it.text == "P" })
    }

    @Test
    fun `surfaces size the z range from their values`() {
        val s = VisualSpec.parse("""{"surface": "x^2 + y^2", "x": [-2, 2], "y": [-2, 2]}""", threeD = true) as Scene3D
        val box = Scene3DBuilder.autoBox(s, DoubleArray(0))
        assertEquals(-2.0, box.xMin, 1e-9)
        assertTrue(box.zMax >= 7.0 && box.zMin <= 0.0)
        assertFalse(Scene3DBuilder.equalAspect(s))
        assertNotNull(Scene3DBuilder.build(s, DoubleArray(0), box, 20).faces.firstOrNull())
    }
}
