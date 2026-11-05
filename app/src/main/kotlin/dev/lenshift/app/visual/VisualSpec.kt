package dev.lenshift.app.visual

import org.json.JSONArray
import org.json.JSONObject

/** A graph or 3D scene parsed from a ```graph or ```graph3d block of an answer. */
sealed interface Visual {
    val title: String?
    val params: List<Param>
    /** Problems with single items, shown under the visual; everything else still draws. */
    val notes: List<String>

    /**
     * Every value expressions can read besides their own variables, in order: slider
     * values, then the coordinates of named points (`A.x`, `A.y`, `A.z`).
     */
    val shared: List<String>
}

/** A slider; expressions and coordinates may use its [name]. [animate] adds a play button. */
data class Param(
    val name: String,
    val value: Double,
    val min: Double,
    val max: Double,
    val step: Double?,
    val animate: Boolean = false
)

enum class Dash { SOLID, DASHED, DOTTED }

/**
 * How an item is drawn. [color] is ARGB, or null for the next palette colour; [fill] is an
 * ARGB fill (with its alpha), or null for the item's default.
 */
data class Style(
    val label: String?,
    val color: Int?,
    val dash: Dash,
    val fill: Int? = null,
    val width: Float? = null,
    val visible: Boolean = true
)

/** A number that may read shared values (`"a"`, `"2a+1"`, `"A.x"`); constants are free. */
class Num private constructor(private val read: ((DoubleArray) -> Double)?, private val value: Double) {
    fun at(values: DoubleArray): Double = read?.invoke(values) ?: value

    companion object {
        fun of(value: Double) = Num(null, value)

        fun of(expr: Expr): Num =
            if (expr.usesVariables) Num({ expr.eval(it) }, 0.0) else Num(null, expr.eval(DoubleArray(0)))

        /** [op] applied to two numbers; stays a constant when both are. */
        fun combine(a: Num, b: Num, op: (Double, Double) -> Double): Num =
            if (a.read == null && b.read == null) Num(null, op(a.value, b.value))
            else Num({ p -> op(a.at(p), b.at(p)) }, 0.0)
    }
}

class Span(val from: Num, val to: Num)

/**
 * An expression of [arity] variables followed by the shared values, bound to the current
 * shared values before sampling so the inner loop allocates nothing.
 */
class Formula(val expr: Expr, private val arity: Int) {
    val source: String get() = expr.source

    fun bind(values: DoubleArray): (Double) -> Double {
        val buf = DoubleArray(arity + values.size)
        values.copyInto(buf, arity)
        return { a ->
            for (i in 0 until arity) buf[i] = a
            expr.eval(buf)
        }
    }

    fun bind2(values: DoubleArray): (Double, Double) -> Double {
        val buf = DoubleArray(arity + values.size)
        values.copyInto(buf, arity)
        return { a, b ->
            buf[0] = a
            buf[1] = b
            expr.eval(buf)
        }
    }

    fun bind3(values: DoubleArray): (Double, Double, Double) -> Double {
        val buf = DoubleArray(arity + values.size)
        values.copyInto(buf, arity)
        return { a, b, c ->
            buf[0] = a
            buf[1] = b
            buf[2] = c
            expr.eval(buf)
        }
    }
}

// ============================================================================== 2D

data class Graph2D(
    override val title: String?,
    override val params: List<Param>,
    override val notes: List<String>,
    override val shared: List<String>,
    val x: Span?,
    val y: Span?,
    /** True: one unit is as long on both axes. Null lets the renderer decide. */
    val equalAspect: Boolean?,
    val axes: Boolean,
    val grid: Boolean,
    val xLabel: String?,
    val yLabel: String?,
    /** Label the axis in multiples of π (trigonometry). */
    val piX: Boolean,
    val piY: Boolean,
    val items: List<Item2>
) : Visual

/** Where a 2D point is: coordinates, another named point, or a construction. */
sealed interface Point2
data class AtXY(val x: Num, val y: Num) : Point2
data class PointRef(val name: String) : Point2
data class MidpointOf(val a: Point2, val b: Point2) : Point2
/** On y = f(x) at [x]; draggable along the curve. */
data class OnGraph(val f: Formula, val x: Num) : Point2
/** On a named circle at [degrees]; draggable around it. */
data class OnCircle(val circle: String, val degrees: Num) : Point2
/** Where two named lines or circles meet; [index] picks one of two. */
data class IntersectionOf(val a: String, val b: String, val index: Int) : Point2

/** A line given by name or by two points. */
sealed interface LineRef
data class NamedLine(val name: String) : LineRef
data class TwoPoints(val a: Point2, val b: Point2) : LineRef

/** How an implicit item compares f(x, y) with 0; [HOLDS] shades where a whole condition is true. */
enum class Relation { EQ, LT, LE, GT, GE, HOLDS }
enum class LineKind { SEGMENT, LINE, RAY }
enum class RiemannType { LEFT, RIGHT, MIDPOINT, TRAPEZOID }
enum class MarkKind { ROOTS, EXTREMA, INTERSECTIONS }
enum class FitType { LINEAR, QUADRATIC, CUBIC, EXPONENTIAL, LOGARITHMIC, POWER }

sealed interface Item2 {
    val style: Style
}

data class FnItem(val name: String?, val f: Formula, val domain: Span?, val ofY: Boolean, override val style: Style) : Item2
data class ParametricItem(val x: Formula, val y: Formula, val t: Span, override val style: Style) : Item2
data class PolarItem(val r: Formula, val theta: Span, override val style: Style) : Item2
data class ImplicitItem(val f: Formula, val relation: Relation, override val style: Style) : Item2
data class DerivativeItem(val f: Formula, override val style: Style) : Item2
/** A curve through points: a smooth spline, or straight pieces (a line chart). */
data class ThroughItem(val points: List<Point2>, val smooth: Boolean, override val style: Style) : Item2
data class PointItem(val name: String?, val at: Point2, val drag: Boolean, override val style: Style) : Item2
data class PointsItem(val points: List<Point2>, val labels: List<String?>, override val style: Style) : Item2
data class LineItem(val name: String?, val a: Point2, val b: Point2, val kind: LineKind, val showLength: Boolean, override val style: Style) : Item2
data class VectorItem(val from: Point2, val to: Point2, override val style: Style) : Item2
data class PerpendicularItem(val name: String?, val line: LineRef, val through: Point2, override val style: Style) : Item2
data class ParallelItem(val name: String?, val line: LineRef, val through: Point2, override val style: Style) : Item2
data class BisectorItem(val name: String?, val a: Point2, val vertex: Point2, val c: Point2, override val style: Style) : Item2
data class PerpBisectorItem(val name: String?, val a: Point2, val b: Point2, override val style: Style) : Item2
/** The tangent (or normal) to y = f(x) at x, or at a point. */
data class TangentItem(val name: String?, val f: Formula, val at: Num?, val point: Point2?, val normal: Boolean, override val style: Style) : Item2
data class CircleItem(val name: String?, val center: Point2, val r: Num?, val through: Point2?, override val style: Style) : Item2
data class TriangleCircleItem(val name: String?, val a: Point2, val b: Point2, val c: Point2, val inscribed: Boolean, override val style: Style) : Item2
/** An arc or sector from [from] to [to] (points) or between angles in degrees. */
data class ArcItem(
    val center: Point2,
    val r: Num?,
    val from: Point2?,
    val to: Point2?,
    val start: Num?,
    val end: Num?,
    val sector: Boolean,
    override val style: Style
) : Item2
data class EllipseItem(val center: Point2, val rx: Num, val ry: Num, val degrees: Num, override val style: Style) : Item2
data class PolygonItem(val points: List<Point2>, val showArea: Boolean, override val style: Style) : Item2
data class RegularPolygonItem(val center: Point2, val r: Num, val n: Int, val degrees: Num, override val style: Style) : Item2
data class AngleItem(val vertex: Point2, val from: Point2, val to: Point2, override val style: Style) : Item2
data class AreaItem(val f: Formula, val g: Formula?, val x: Span, val showValue: Boolean, override val style: Style) : Item2
data class RiemannItem(val f: Formula, val x: Span, val n: Num, val type: RiemannType, val showValue: Boolean, override val style: Style) : Item2
data class MarkItem(val kind: MarkKind, val f: Formula, val g: Formula?, val showCoordinates: Boolean, override val style: Style) : Item2
/** dy/dx = slope(x, y), or arrows (vx, vy). */
data class FieldItem(val slope: Formula?, val vx: Formula?, val vy: Formula?, override val style: Style) : Item2
data class BarsItem(val bars: List<Pair<Num, Num>>, val width: Num?, val labels: List<String?>, override val style: Style) : Item2
data class PieItem(val values: List<Num>, val labels: List<String?>, val colors: List<Int?>, override val style: Style) : Item2
data class FitItem(val points: List<Point2>, val type: FitType, val showEquation: Boolean, override val style: Style) : Item2
data class TextItem(val at: Point2, val text: String, override val style: Style) : Item2

// ============================================================================== 3D

data class Scene3D(
    override val title: String?,
    override val params: List<Param>,
    override val notes: List<String>,
    override val shared: List<String>,
    val x: Span?,
    val y: Span?,
    val z: Span?,
    val equalAspect: Boolean?,
    val axes: Boolean,
    val grid: Boolean,
    val items: List<Item3>
) : Visual

sealed interface Point3
data class AtXYZ(val x: Num, val y: Num, val z: Num) : Point3
data class Point3Ref(val name: String) : Point3
data class Midpoint3(val a: Point3, val b: Point3) : Point3

sealed interface PlaneDef
data class PlaneEquation(val f: Formula) : PlaneDef
data class PlanePointNormal(val point: Point3?, val normal: Point3, val d: Num?) : PlaneDef
data class PlaneThroughPoints(val a: Point3, val b: Point3, val c: Point3) : PlaneDef

sealed interface Item3 {
    val style: Style
}

data class SurfaceItem(val z: Formula, override val style: Style) : Item3
data class ParamSurfaceItem(val x: Formula, val y: Formula, val z: Formula, val u: Span, val v: Span, override val style: Style) : Item3
data class CurveItem(val x: Formula, val y: Formula, val z: Formula, val t: Span, override val style: Style) : Item3
data class Point3Item(val name: String?, val at: Point3, override val style: Style) : Item3
data class Points3Item(val points: List<Point3>, val labels: List<String?>, override val style: Style) : Item3
data class Line3Item(val name: String?, val a: Point3, val b: Point3, val kind: LineKind, override val style: Style) : Item3
data class Vector3Item(val from: Point3, val to: Point3, override val style: Style) : Item3
data class PlaneItem(val name: String?, val plane: PlaneDef, override val style: Style) : Item3
data class SphereItem(val name: String?, val center: Point3, val r: Num, override val style: Style) : Item3
data class BoxItem(val min: Point3, val max: Point3, override val style: Style) : Item3
data class CylinderItem(val from: Point3, val to: Point3, val r: Num, override val style: Style) : Item3
data class ConeItem(val base: Point3, val apex: Point3, val r: Num, override val style: Style) : Item3
data class PolyhedronItem(val vertices: List<Point3>, val faces: List<IntArray>, override val style: Style) : Item3
data class Polygon3Item(val points: List<Point3>, override val style: Style) : Item3
data class Circle3Item(val center: Point3, val normal: Point3, val r: Num, override val style: Style) : Item3
/** Where two named planes, spheres or lines meet: a line, a circle or points. */
data class Intersection3Item(val a: String, val b: String, override val style: Style) : Item3
data class Text3Item(val at: Point3, val text: String, override val style: Style) : Item3

class SpecError(message: String) : IllegalArgumentException(message)

/**
 * Parses visual specs. Lenient where models slip (comments, trailing commas, single
 * quotes, bare keys, LaTeX backslashes in labels) and strict about what gets drawn: an
 * item that cannot be understood is skipped with a note instead of failing the visual.
 *
 * Items may be named and referred to by later items: points by name in place of
 * coordinates ("A") and in expressions (`A.x`), lines and circles in constructions and
 * intersections, and functions in expressions (`f(a)`, `f'(a)`) and analysis items.
 */
object VisualSpec {

    const val MAX_ITEMS = 80
    const val MAX_PARAMS = 8
    const val MAX_POINTS = 2_000
    const val MAX_FACES = 2_000

    private val KINDS_2D = listOf(
        "fn", "function", "y", "parametric", "polar", "implicit", "equation", "inequality", "derivative",
        "spline", "polyline", "point", "points", "segment", "line", "ray", "vector", "perpendicular",
        "parallel", "bisector", "perpendicularBisector", "tangent", "normal", "circle", "circumcircle",
        "incircle", "arc", "sector", "ellipse", "polygon", "regularPolygon", "angle", "area", "riemann",
        "roots", "extrema", "intersections", "slopefield", "vectorfield", "bars", "pie", "fit", "text"
    )
    private val KINDS_3D = listOf(
        "surface", "curve", "point", "points", "segment", "line", "ray", "vector", "plane", "sphere",
        "box", "cube", "cylinder", "cone", "polyhedron", "polygon", "circle", "intersection", "text"
    )

    /** The axes: no slider or item may take these names. */
    private val AXES = setOf("x", "y", "z")
    /**
     * Curve parameters. A slider may use them (inside a curve its own parameter wins), but
     * an item may not: its name would read as the parameter.
     */
    private val PARAMETERS = setOf("t", "u", "v", "theta", "r")
    private val NAME = Regex("^[A-Za-z][A-Za-z0-9_]{0,15}$")

    /** Names models give unnamed curves when they refer to them: f, g, h in order. */
    private val FUNCTION_NAMES = listOf("f", "g", "h")

    /** Words and symbols that join conditions: "y > x^2 and y < 2". */
    private val LOGIC = Regex("""(?i)(?<![A-Za-z])(?:and|or|not)(?![A-Za-z])|&&|[∧∨¬]|\\(?:land|lor|lnot|wedge|vee|neg)(?![A-Za-z])""")

    private enum class Kind { POINT, LINE, CIRCLE, FUNCTION, PLANE, SPHERE }

    fun parse(source: String, threeD: Boolean): Visual {
        val cleaned = cleanJson(source)
        val json = try {
            JSONObject(cleaned)
        } catch (e: Exception) {
            throw SpecError("The spec is not valid JSON")
        }
        val notes = mutableListOf<String>()
        val params = parseParams(json.opt("params"), notes)

        val rawItems: List<Any?> = when (val items = json.opt("items")) {
            is JSONArray -> (0 until items.length()).map { items.opt(it) }
            is JSONObject -> listOf(items)
            // A single item written as the whole spec: {"fn": "x^2"}.
            else -> listOf(json)
        }
        if (rawItems.size > MAX_ITEMS) notes += "Only the first $MAX_ITEMS items are drawn."
        val items = rawItems.take(MAX_ITEMS)

        // Pass 1: names, so the shared values have one fixed layout for every expression.
        val kinds = linkedMapOf<String, Kind>()
        items.forEachIndexed { i, raw ->
            val obj = raw as? JSONObject ?: return@forEachIndexed
            val name = obj.optString("name").trim().takeIf { it.isNotEmpty() && it != "null" } ?: return@forEachIndexed
            val kind = nameKind(obj, threeD) ?: return@forEachIndexed
            when {
                // Names like O' or r are meant as labels: draw the item with it as its label.
                !NAME.matches(name) || name in AXES || name in PARAMETERS || Expr.isReserved(name) -> {
                    obj.remove("name")
                    if (!obj.has("label")) obj.put("label", name)
                }
                name in kinds || params.any { it.name == name } ->
                    notes += "Item ${i + 1}: the name '$name' is used twice."
                else -> kinds[name] = kind
            }
        }
        autoName(items, kinds, params, cleaned, threeD)
        val axes = if (threeD) listOf("x", "y", "z") else listOf("x", "y")
        val shared = params.map { it.name } + kinds.filterValues { it == Kind.POINT }.keys.flatMap { n -> axes.map { "$n.$it" } }
        val ctx = Ctx(shared, kinds, notes)

        val title = json.optString("title").trim().takeIf { it.isNotEmpty() && it != "null" }
        val equal = when (json.optString("aspect").lowercase()) {
            "equal", "1:1", "square" -> true
            "auto", "fit", "free" -> false
            else -> null
        }
        val showAxes = json.optBoolean("axes", true)
        val showGrid = json.optBoolean("grid", true)

        // A bad view range is noted and replaced by an automatic one.
        fun view(axis: String): Span? = try {
            ctx.span(json.opt(axis))
        } catch (e: IllegalArgumentException) {
            notes += "The $axis range: ${e.message}"
            null
        }

        return if (threeD) {
            val parsed = items.mapIndexedNotNull { i, raw -> item(raw, i, notes) { ctx.item3(it) } }
            if (parsed.isEmpty()) throw SpecError(notes.firstOrNull() ?: "The spec has nothing to draw")
            Scene3D(title, params, notes, shared, view("x"), view("y"), view("z"), equal, showAxes, showGrid, parsed)
        } else {
            val parsed = items.mapIndexedNotNull { i, raw -> item(raw, i, notes) { ctx.item2(it) } }
            if (parsed.isEmpty()) throw SpecError(notes.firstOrNull() ?: "The spec has nothing to draw")
            val ticks = json.opt("ticks")
            Graph2D(
                title, params, notes, shared, view("x"), view("y"), equal, showAxes, showGrid,
                json.optString("xLabel").trim().takeIf { it.isNotEmpty() },
                json.optString("yLabel").trim().takeIf { it.isNotEmpty() },
                piX = ticks == "pi" || (ticks as? JSONObject)?.optString("x") == "pi",
                piY = (ticks as? JSONObject)?.optString("y") == "pi",
                items = parsed
            )
        }
    }

    /**
     * Models often draw a curve or circle without a name and then refer to it as f (g, h for
     * later curves) or c1 (c2, …). Such an item gets that name, but only when the spec
     * mentions it and nothing else has it, so a spec that never says "f" is untouched.
     */
    private fun autoName(items: List<Any?>, kinds: MutableMap<String, Kind>, params: List<Param>, source: String, threeD: Boolean) {
        fun free(name: String) = name !in kinds && params.none { it.name == name }
        fun mentioned(name: String) = Regex("""(?<![A-Za-z0-9_.\\])${Regex.escape(name)}(?![A-Za-z0-9_])""").containsMatchIn(source)
        val functionNames = FUNCTION_NAMES.filter(::free).iterator()
        var circles = 0
        for (raw in items) {
            val obj = raw as? JSONObject ?: continue
            if (obj.optString("name").trim().let { it.isNotEmpty() && it != "null" }) continue
            val name = when (nameKind(obj, threeD)) {
                Kind.FUNCTION -> {
                    val curve = obj.opt("fn") ?: obj.opt("function") ?: obj.opt("y")
                    // x = g(y) is not a function of x, so it cannot be called f.
                    if (curve is String && Regex("""^\s*x\s*=""").containsMatchIn(curve)) continue
                    if (functionNames.hasNext()) functionNames.next() else continue
                }
                Kind.CIRCLE -> "c${++circles}"
                else -> continue
            }
            if (free(name) && mentioned(name)) {
                obj.put("name", name)
                kinds[name] = nameKind(obj, threeD) ?: continue
            }
        }
    }

    /** What a named item can be referred to as, or null if it can't be. */
    private fun nameKind(o: JSONObject, threeD: Boolean): Kind? {
        val kinds = if (threeD) KINDS_3D else KINDS_2D
        return when (kinds.firstOrNull { o.has(it) }) {
            "point" -> Kind.POINT
            "segment", "line", "ray", "perpendicular", "parallel", "bisector", "perpendicularBisector", "tangent", "normal" -> Kind.LINE
            "circle", "circumcircle", "incircle" -> if (threeD) null else Kind.CIRCLE
            "fn", "function", "y" -> if (threeD) null else Kind.FUNCTION
            "plane" -> Kind.PLANE
            "sphere" -> Kind.SPHERE
            else -> null
        }
    }

    private fun <T> item(raw: Any?, index: Int, notes: MutableList<String>, parse: (JSONObject) -> T?): T? {
        val obj = raw as? JSONObject ?: run {
            notes += "Item ${index + 1} is not an object."
            return null
        }
        return try {
            parse(obj) ?: run {
                notes += "Item ${index + 1}: nothing Lenshift knows how to draw."
                null
            }
        } catch (e: IllegalArgumentException) {
            notes += "Item ${index + 1}: ${e.message}"
            null
        }
    }

    private fun parseParams(raw: Any?, notes: MutableList<String>): List<Param> {
        val entries: List<Pair<String, Any?>> = when (raw) {
            is JSONObject -> raw.keys().asSequence().map { it to raw.opt(it) }.toList()
            is JSONArray -> (0 until raw.length()).mapNotNull { i ->
                (raw.opt(i) as? JSONObject)?.let { it.optString("name") to it }
            }
            else -> emptyList()
        }
        val out = mutableListOf<Param>()
        for ((name, spec) in entries) {
            if (out.size >= MAX_PARAMS) {
                notes += "Only $MAX_PARAMS sliders are shown."
                break
            }
            if (!NAME.matches(name) || name in AXES || Expr.isReserved(name)) {
                notes += "Slider '$name' needs a different name."
                continue
            }
            try {
                val obj = spec as? JSONObject
                val value = number(obj?.opt("value") ?: spec) ?: 1.0
                var min = number(obj?.opt("min")) ?: minOf(-10.0, value)
                var max = number(obj?.opt("max")) ?: maxOf(10.0, value)
                if (min > max) min = max.also { max = min }
                if (min == max) max = min + 1
                val step = number(obj?.opt("step"))?.takeIf { it > 0 }
                out += Param(name, value.coerceIn(min, max), min, max, step, obj?.optBoolean("animate", false) == true)
            } catch (e: IllegalArgumentException) {
                notes += "Slider '$name': ${e.message}"
            }
        }
        return out
    }

    /** A plain number, or a constant expression such as "2pi". */
    private fun number(raw: Any?): Double? = when (raw) {
        null, JSONObject.NULL -> null
        is Number -> raw.toDouble()
        is String -> Expr.constant(raw)
        else -> throw SpecError("'$raw' is not a number")
    }

    // ------------------------------------------------------------------ items

    private class Ctx(
        val shared: List<String>,
        val kinds: Map<String, Kind>,
        val notes: MutableList<String>
    ) {
        /** Named functions defined so far; later expressions may call them. */
        private val functions = linkedMapOf<String, UserFunction>()
        private val functionFormulas = mutableMapOf<String, Formula>()
        /** Named curves written x = g(y); those with a constant g are vertical lines. */
        private val sideways = mutableSetOf<String>()
        private val verticals = mutableMapOf<String, Num>()

        fun item2(o: JSONObject): Item2? {
            val kind = KINDS_2D.firstOrNull { o.has(it) } ?: return null
            val style = style(o)
            val name = o.optString("name").trim().takeIf { it in kinds }
            val v = o.opt(kind)
            return when (kind) {
                "fn", "function", "y" -> {
                    val text = string(v)
                    val inverse = Regex("""^\s*x\s*=""").containsMatchIn(text)
                    val body = text.replace(Regex("""^\s*[xy]\s*=\s*"""), "")
                    val f = if (inverse) formula(body, listOf("y")) else formula(body, listOf("x"))
                    if (name != null && !inverse) {
                        functions[name] = UserFunction(f.expr, shared.size)
                        functionFormulas[name] = f
                    }
                    if (name != null && inverse) {
                        sideways += name
                        runCatching { num(body) }.getOrNull()?.let { verticals[name] = it }
                    }
                    FnItem(name, f, span(o.opt("domain") ?: o.opt("x")), inverse, style)
                }
                "parametric" -> {
                    val parts = strings(v, 2)
                    ParametricItem(formula(parts[0], listOf("t")), formula(parts[1], listOf("t")), span(o.opt("t")) ?: turn(), style)
                }
                "polar" -> PolarItem(
                    formula(string(v).replace(Regex("""^\s*r\s*=\s*"""), ""), listOf("theta", "t")),
                    span(o.opt("theta") ?: o.opt("t")) ?: turn(),
                    style
                )
                "implicit", "equation", "inequality" -> {
                    val (expr, relation) = relation(string(v))
                    ImplicitItem(formula(expr, listOf("x", "y")), relation, style)
                }
                "derivative" -> DerivativeItem(fn(v), style)
                "spline", "polyline" -> ThroughItem(pointList(v, 2), smooth = kind == "spline", style = style)
                "point" -> PointItem(name, point(v), o.optBoolean("drag", false), style)
                "points" -> {
                    val list = pointList(v, 1)
                    val labels = o.optJSONArray("labels")
                    PointsItem(list, list.indices.map { labels?.optString(it)?.takeIf { s -> s.isNotBlank() } }, style)
                }
                "segment", "line", "ray" -> {
                    val (a, b) = twoPoints(v)
                    val lineKind = when (kind) {
                        "segment" -> LineKind.SEGMENT
                        "ray" -> LineKind.RAY
                        else -> LineKind.LINE
                    }
                    LineItem(name, a, b, lineKind, o.optBoolean("showLength", false), style)
                }
                "vector" -> {
                    val (a, b) = vector(v)
                    VectorItem(a, b, style)
                }
                "perpendicular", "parallel" -> {
                    val c = v as? JSONObject ?: throw SpecError("$kind needs a line and a point to pass through")
                    val line = lineRef(c.opt("to") ?: c.opt("line") ?: throw SpecError("$kind needs a line"))
                    val through = point(c.opt("through") ?: c.opt("point") ?: throw SpecError("$kind needs a point"))
                    if (kind == "perpendicular") PerpendicularItem(name, line, through, style) else ParallelItem(name, line, through, style)
                }
                "bisector" -> {
                    val list = pointList(v, 3)
                    if (list.size != 3) throw SpecError("bisector takes three points: one side, the vertex, the other side")
                    BisectorItem(name, list[0], list[1], list[2], style)
                }
                "perpendicularBisector" -> {
                    val (a, b) = twoPoints(v)
                    PerpBisectorItem(name, a, b, style)
                }
                "tangent", "normal" -> {
                    val t = v as? JSONObject ?: throw SpecError("$kind needs fn and at")
                    val f = fn(t.opt("fn") ?: t.opt("function") ?: throw SpecError("$kind needs fn"))
                    val atRaw = t.opt("at") ?: t.opt("x")
                    val point = if (atRaw is String && atRaw in kinds && kinds[atRaw] == Kind.POINT) PointRef(atRaw) else null
                    TangentItem(name, f, if (point == null) num(atRaw) else null, point, kind == "normal", style)
                }
                "circle" -> {
                    val c = v as? JSONObject ?: throw SpecError("circle needs a center and r")
                    val r = (c.opt("r") ?: c.opt("radius"))?.takeIf { it != JSONObject.NULL }?.let { num(it) }
                    val through = c.opt("through")?.let { point(it) }
                    if (r == null && through == null) throw SpecError("circle needs r or a point it passes through")
                    CircleItem(name, point(c.opt("center") ?: throw SpecError("circle needs a center")), r, through, style)
                }
                "circumcircle", "incircle" -> {
                    val list = pointList(v, 3)
                    if (list.size != 3) throw SpecError("$kind takes the triangle's three points")
                    TriangleCircleItem(name, list[0], list[1], list[2], kind == "incircle", style)
                }
                "arc", "sector" -> {
                    val a = v as? JSONObject ?: throw SpecError("$kind needs a center")
                    val r = (a.opt("r") ?: a.opt("radius"))?.takeIf { it != JSONObject.NULL }?.let { num(it) }
                    val from = a.opt("from")?.let { point(it) }
                    val to = a.opt("to")?.let { point(it) }
                    val start = a.opt("start")?.let { num(it) }
                    val end = a.opt("end")?.let { num(it) }
                    if ((from == null || to == null) && (r == null || start == null || end == null)) {
                        throw SpecError("$kind needs from and to points, or r with start and end angles")
                    }
                    ArcItem(point(a.opt("center") ?: throw SpecError("$kind needs a center")), r, from, to, start, end, kind == "sector", style)
                }
                "ellipse" -> {
                    val e = v as? JSONObject ?: throw SpecError("ellipse needs center, rx and ry")
                    EllipseItem(
                        e.opt("center")?.let { point(it) } ?: origin(),
                        num(e.opt("rx") ?: e.opt("a")),
                        num(e.opt("ry") ?: e.opt("b")),
                        e.opt("angle")?.let { num(it) } ?: Num.of(0.0),
                        style
                    )
                }
                "polygon" -> {
                    val list = pointList(v, 3)
                    PolygonItem(list, o.optBoolean("showArea", false), style)
                }
                "regularPolygon" -> {
                    val p = v as? JSONObject ?: throw SpecError("regularPolygon needs center, r and n")
                    val n = p.optInt("n", p.optInt("sides", 0))
                    if (n !in 3..64) throw SpecError("regularPolygon needs 3 to 64 sides")
                    RegularPolygonItem(
                        p.opt("center")?.let { point(it) } ?: origin(),
                        num(p.opt("r") ?: p.opt("radius") ?: 1),
                        n,
                        p.opt("angle")?.let { num(it) } ?: Num.of(90.0),
                        style
                    )
                }
                "angle" -> {
                    val a = v as? JSONObject ?: throw SpecError("angle needs vertex, from and to")
                    AngleItem(point(a.opt("vertex")), point(a.opt("from")), point(a.opt("to")), style)
                }
                "area" -> {
                    val a = v as? JSONObject ?: throw SpecError("area needs fn and x")
                    val between = a.optJSONArray("between")
                    val f = fn(between?.opt(0) ?: a.opt("fn") ?: a.opt("function") ?: a.opt("under"))
                    val g = (between?.opt(1) ?: a.opt("to"))?.takeIf { it != JSONObject.NULL }?.let { fn(it) }
                    AreaItem(
                        f, g,
                        span(a.opt("x") ?: a.opt("domain")) ?: throw SpecError("area needs an x range"),
                        a.optBoolean("showValue", o.optBoolean("showValue", true)),
                        style
                    )
                }
                "riemann" -> {
                    val r = v as? JSONObject ?: throw SpecError("riemann needs fn, x and n")
                    val type = when (r.optString("type").lowercase()) {
                        "right" -> RiemannType.RIGHT
                        "mid", "midpoint", "middle" -> RiemannType.MIDPOINT
                        "trapezoid", "trapezoidal", "trapezium" -> RiemannType.TRAPEZOID
                        else -> RiemannType.LEFT
                    }
                    RiemannItem(
                        fn(r.opt("fn") ?: r.opt("function")),
                        span(r.opt("x")) ?: throw SpecError("riemann needs an x range"),
                        num(r.opt("n") ?: 8),
                        type,
                        r.optBoolean("showValue", true),
                        style
                    )
                }
                "roots", "extrema" -> MarkItem(
                    if (kind == "roots") MarkKind.ROOTS else MarkKind.EXTREMA,
                    fn(v), null, o.optBoolean("showCoordinates", true), style
                )
                "intersections" -> {
                    val pair = v as? JSONArray ?: throw SpecError("intersections takes two functions")
                    if (pair.length() != 2) throw SpecError("intersections takes two functions")
                    val a = pair.opt(0)
                    val b = pair.opt(1)
                    // A vertical line x = c meets y = f(x) once, at (c, f(c)).
                    val lineA = (a as? String)?.trim()?.let { verticals[it] }
                    val lineB = (b as? String)?.trim()?.let { verticals[it] }
                    val marked = style.copy(label = style.label ?: o.optString("name").trim().takeIf { it.isNotEmpty() && it != "null" })
                    when {
                        lineA != null && lineB == null -> PointItem(null, OnGraph(fn(b), lineA), false, marked)
                        lineB != null && lineA == null -> PointItem(null, OnGraph(fn(a), lineB), false, marked)
                        else -> MarkItem(MarkKind.INTERSECTIONS, fn(a), fn(b), o.optBoolean("showCoordinates", true), style)
                    }
                }
                "slopefield" -> FieldItem(formula(string(v).replace(Regex("""^\s*(dy/dx|y')\s*=\s*"""), ""), listOf("x", "y")), null, null, style)
                "vectorfield" -> {
                    val parts = strings(v, 2)
                    FieldItem(null, formula(parts[0], listOf("x", "y")), formula(parts[1], listOf("x", "y")), style)
                }
                "bars" -> {
                    val arr = v as? JSONArray ?: throw SpecError("bars must be a list of [x, height]")
                    if (arr.length() > 200) throw SpecError("too many bars")
                    val bars = (0 until arr.length()).map { i ->
                        when (val bar = arr.opt(i)) {
                            is JSONArray -> num(bar.opt(0)) to num(bar.opt(1))
                            // Heights alone sit at x = 1, 2, 3, …
                            else -> Num.of(i + 1.0) to num(bar)
                        }
                    }
                    val labels = o.optJSONArray("labels")
                    BarsItem(
                        bars, o.opt("width")?.let { num(it) },
                        bars.indices.map { labels?.optString(it)?.takeIf { s -> s.isNotBlank() } },
                        style
                    )
                }
                "pie" -> {
                    val arr = v as? JSONArray ?: throw SpecError("pie must be a list of values")
                    if (arr.length() !in 1..24) throw SpecError("pie takes 1 to 24 values")
                    val labels = o.optJSONArray("labels")
                    val colors = o.optJSONArray("colors")
                    PieItem(
                        (0 until arr.length()).map { num(arr.opt(it)) },
                        (0 until arr.length()).map { labels?.optString(it)?.takeIf { s -> s.isNotBlank() } },
                        (0 until arr.length()).map { parseColor(colors?.opt(it)) },
                        style
                    )
                }
                "fit" -> {
                    val f = v as? JSONObject ?: throw SpecError("fit needs points and a type")
                    val type = when (f.optString("type").lowercase()) {
                        "quadratic", "poly2" -> FitType.QUADRATIC
                        "cubic", "poly3" -> FitType.CUBIC
                        "exponential", "exp" -> FitType.EXPONENTIAL
                        "logarithmic", "log", "ln" -> FitType.LOGARITHMIC
                        "power" -> FitType.POWER
                        else -> FitType.LINEAR
                    }
                    FitItem(pointList(f.opt("points"), 2), type, f.optBoolean("showEquation", true), style)
                }
                "text" -> TextItem(point(o.opt("at") ?: throw SpecError("text needs at")), string(v), style)
                else -> null
            }
        }

        fun item3(o: JSONObject): Item3? {
            val kind = KINDS_3D.firstOrNull { o.has(it) } ?: return null
            val style = style(o)
            val name = o.optString("name").trim().takeIf { it in kinds }
            val v = o.opt(kind)
            return when (kind) {
                "surface" -> when (v) {
                    is String -> SurfaceItem(formula(v.replace(Regex("""^\s*z\s*=\s*"""), ""), listOf("x", "y")), style)
                    is JSONArray -> {
                        val parts = strings(v, 3)
                        val vars = listOf("u", "v")
                        ParamSurfaceItem(
                            formula(parts[0], vars), formula(parts[1], vars), formula(parts[2], vars),
                            span(o.opt("u")) ?: turn(), span(o.opt("v")) ?: turn(), style
                        )
                    }
                    else -> throw SpecError("surface is a formula in x and y, or [x(u,v), y(u,v), z(u,v)]")
                }
                "curve" -> {
                    val parts = strings(v, 3)
                    val vars = listOf("t")
                    CurveItem(formula(parts[0], vars), formula(parts[1], vars), formula(parts[2], vars), span(o.opt("t")) ?: turn(), style)
                }
                "point" -> Point3Item(name, point3(v), style)
                "points" -> {
                    val arr = v as? JSONArray ?: throw SpecError("points must be a list of [x, y, z]")
                    if (arr.length() > MAX_POINTS) throw SpecError("too many points")
                    val labels = o.optJSONArray("labels")
                    Points3Item(
                        (0 until arr.length()).map { point3(arr.opt(it)) },
                        (0 until arr.length()).map { labels?.optString(it)?.takeIf { s -> s.isNotBlank() } },
                        style
                    )
                }
                "segment", "ray" -> twoPoints3(v).let { (a, b) ->
                    Line3Item(name, a, b, if (kind == "ray") LineKind.RAY else LineKind.SEGMENT, style)
                }
                "line" -> when (v) {
                    is JSONObject -> if (v.has("dir") || v.has("direction")) {
                        val point = point3(v.opt("point") ?: v.opt("through") ?: throw SpecError("line needs a point"))
                        val dir = point3(v.opt("dir") ?: v.opt("direction"))
                        Line3Item(name, point, sum3(point, dir), LineKind.LINE, style)
                    } else {
                        twoPoints3(v).let { (a, b) -> Line3Item(name, a, b, LineKind.LINE, style) }
                    }
                    else -> twoPoints3(v).let { (a, b) -> Line3Item(name, a, b, LineKind.LINE, style) }
                }
                "vector" -> when {
                    v is JSONArray && (v.opt(0) is JSONArray || v.opt(0) is String) && v.length() == 2 ->
                        twoPoints3(v).let { (a, b) -> Vector3Item(a, b, style) }
                    v is JSONObject -> Vector3Item(
                        v.opt("from")?.let { point3(it) } ?: origin3(),
                        point3(v.opt("to") ?: throw SpecError("vector needs to")),
                        style
                    )
                    else -> Vector3Item(origin3(), point3(v), style)
                }
                "plane" -> PlaneItem(name, plane(v), style)
                "sphere" -> {
                    val s = v as? JSONObject ?: throw SpecError("sphere needs center and r")
                    SphereItem(name, s.opt("center")?.let { point3(it) } ?: origin3(), num(s.opt("r") ?: s.opt("radius") ?: 1), style)
                }
                "box", "cube" -> {
                    val b = v as? JSONObject ?: throw SpecError("$kind needs min and max, or center and size")
                    if (b.has("min")) {
                        BoxItem(point3(b.opt("min")), point3(b.opt("max")), style)
                    } else {
                        val center = b.opt("center")?.let { point3(it) } ?: origin3()
                        val size = b.opt("size") ?: b.opt("side") ?: 1
                        val half = if (size is JSONArray) {
                            AtXYZ(half(num(size.opt(0))), half(num(size.opt(1))), half(num(size.opt(2))))
                        } else {
                            val h = half(num(size))
                            AtXYZ(h, h, h)
                        }
                        BoxItem(diff3(center, half), sum3(center, half), style)
                    }
                }
                "cylinder" -> {
                    val c = v as? JSONObject ?: throw SpecError("cylinder needs from, to and r")
                    val r = num(c.opt("r") ?: c.opt("radius") ?: 1)
                    if (c.has("from")) {
                        CylinderItem(point3(c.opt("from")), point3(c.opt("to")), r, style)
                    } else {
                        val center = c.opt("center")?.let { point3(it) } ?: origin3()
                        val h = half(num(c.opt("height") ?: c.opt("h") ?: 1))
                        val zero = Num.of(0.0)
                        CylinderItem(diff3(center, AtXYZ(zero, zero, h)), sum3(center, AtXYZ(zero, zero, h)), r, style)
                    }
                }
                "cone" -> {
                    val c = v as? JSONObject ?: throw SpecError("cone needs base, apex and r")
                    ConeItem(point3(c.opt("base") ?: throw SpecError("cone needs a base")), point3(c.opt("apex") ?: throw SpecError("cone needs an apex")), num(c.opt("r") ?: c.opt("radius") ?: 1), style)
                }
                "polyhedron" -> when (v) {
                    is String -> preset(v, o)
                    is JSONObject -> {
                        val vs = v.optJSONArray("vertices") ?: throw SpecError("polyhedron needs vertices")
                        val fs = v.optJSONArray("faces") ?: throw SpecError("polyhedron needs faces")
                        if (vs.length() > MAX_POINTS || fs.length() > MAX_FACES) throw SpecError("polyhedron is too large")
                        val vertices = (0 until vs.length()).map { point3(vs.opt(it)) }
                        val faces = (0 until fs.length()).map { i ->
                            val f = fs.opt(i) as? JSONArray ?: throw SpecError("each face is a list of vertex numbers")
                            IntArray(f.length()) { k ->
                                val index = f.optInt(k, -1)
                                if (index !in vertices.indices) throw SpecError("face ${i + 1} uses a missing vertex")
                                index
                            }.also { if (it.size < 3) throw SpecError("face ${i + 1} needs 3 or more vertices") }
                        }
                        PolyhedronItem(vertices, faces, style)
                    }
                    else -> throw SpecError("polyhedron is a preset name or {vertices, faces}")
                }
                "polygon" -> {
                    val arr = v as? JSONArray ?: throw SpecError("polygon must be a list of [x, y, z]")
                    if (arr.length() !in 3..MAX_POINTS) throw SpecError("polygon needs at least 3 points")
                    Polygon3Item((0 until arr.length()).map { point3(arr.opt(it)) }, style)
                }
                "circle" -> {
                    val c = v as? JSONObject ?: throw SpecError("circle needs center, normal and r")
                    Circle3Item(
                        c.opt("center")?.let { point3(it) } ?: origin3(),
                        c.opt("normal")?.let { point3(it) } ?: AtXYZ(Num.of(0.0), Num.of(0.0), Num.of(1.0)),
                        num(c.opt("r") ?: c.opt("radius") ?: 1),
                        style
                    )
                }
                "intersection" -> {
                    val pair = v as? JSONArray ?: throw SpecError("intersection takes two names")
                    if (pair.length() != 2) throw SpecError("intersection takes two names")
                    val a = pair.optString(0)
                    val b = pair.optString(1)
                    for (n in listOf(a, b)) if (n !in kinds) throw SpecError("'$n' is not a named plane, sphere or line")
                    Intersection3Item(a, b, style)
                }
                "text" -> Text3Item(point3(o.opt("at") ?: throw SpecError("text needs at")), string(v), style)
                else -> null
            }
        }

        // -------------------------------------------------------------- values

        fun formula(text: String, vars: List<String>): Formula =
            Formula(Expr.parse(text, vars + shared, functions), vars.size)

        /** A named function, an expression in x, or either wrapped as {"fn": …}. */
        fun fn(raw: Any?): Formula {
            if (raw is JSONObject) {
                return fn(raw.opt("fn") ?: raw.opt("function") ?: raw.opt("y") ?: throw SpecError("expected a function"))
            }
            val text = string(raw)
            functionFormulas[text]?.let { return it }
            if (text in sideways) throw SpecError("'$text' is a curve x = …, not a function of x")
            if (kinds[text] == Kind.FUNCTION) throw SpecError("'$text' must be defined before it is used")
            return formula(text.replace(Regex("""^\s*y\s*=\s*"""), ""), listOf("x"))
        }

        fun num(raw: Any?): Num = when (raw) {
            null, JSONObject.NULL -> throw SpecError("a number is missing")
            is Number -> Num.of(raw.toDouble())
            is String -> Num.of(Expr.parse(raw, shared, functions))
            else -> throw SpecError("'$raw' is not a number")
        }

        fun span(raw: Any?): Span? {
            val arr = raw as? JSONArray ?: return null
            if (arr.length() != 2) throw SpecError("a range is [from, to]")
            return Span(num(arr.opt(0)), num(arr.opt(1)))
        }

        fun point(raw: Any?): Point2 = when (raw) {
            is JSONArray -> {
                if (raw.length() < 2) throw SpecError("a point is [x, y]")
                AtXY(num(raw.opt(0)), num(raw.opt(1)))
            }
            is String -> {
                val name = raw.trim()
                if (kinds[name] != Kind.POINT) throw SpecError("'$name' is not a named point")
                PointRef(name)
            }
            is JSONObject -> when {
                raw.has("midpoint") -> {
                    val (a, b) = twoPoints(raw.opt("midpoint"))
                    MidpointOf(a, b)
                }
                raw.has("intersection") -> {
                    val pair = raw.optJSONArray("intersection") ?: throw SpecError("intersection takes two names")
                    val a = pair.optString(0)
                    val b = pair.optString(1)
                    for (n in listOf(a, b)) {
                        val k = kinds[n]
                        if (k != Kind.LINE && k != Kind.CIRCLE) throw SpecError("'$n' is not a named line or circle")
                    }
                    IntersectionOf(a, b, raw.optInt("index", 0))
                }
                raw.has("on") -> {
                    val on = raw.opt("on")
                    if (on is String && kinds[on.trim()] == Kind.CIRCLE) {
                        OnCircle(on.trim(), num(raw.opt("angle") ?: 0))
                    } else {
                        OnGraph(fn(on), num(raw.opt("x") ?: 0))
                    }
                }
                raw.has("x") -> AtXY(num(raw.opt("x")), num(raw.opt("y")))
                // A point item written in place of a point: {"point": [1, 2]}.
                raw.has("point") -> point(raw.opt("point"))
                raw.has("at") -> point(raw.opt("at"))
                else -> throw SpecError("a point is [x, y], a name, or {midpoint | intersection | on}")
            }
            else -> throw SpecError("a point is [x, y]")
        }

        /** A list of points (at least [min]); names are allowed in place of coordinates. */
        fun pointList(raw: Any?, min: Int): List<Point2> {
            val arr = raw as? JSONArray ?: throw SpecError("expected a list of points")
            if (arr.length() < min) throw SpecError("needs at least $min points")
            if (arr.length() > MAX_POINTS) throw SpecError("too many points")
            return (0 until arr.length()).map { point(arr.opt(it)) }
        }

        private fun twoPoints(raw: Any?): Pair<Point2, Point2> = when (raw) {
            is JSONArray -> {
                if (raw.length() != 2) throw SpecError("use two points [[x1, y1], [x2, y2]]")
                point(raw.opt(0)) to point(raw.opt(1))
            }
            is JSONObject -> when {
                raw.has("through") -> twoPoints(raw.opt("through"))
                raw.has("from") -> point(raw.opt("from")) to point(raw.opt("to"))
                raw.has("point") && raw.has("slope") -> {
                    val p = point(raw.opt("point"))
                    val m = num(raw.opt("slope"))
                    p to SlopeStep(p, m).target()
                }
                else -> throw SpecError("use two points, or point and slope")
            }
            else -> throw SpecError("use two points [[x1, y1], [x2, y2]]")
        }

        /** The second point of a line through [p] with slope [m]: one unit to the right. */
        private inner class SlopeStep(val p: Point2, val m: Num) {
            fun target(): Point2 = when (p) {
                is AtXY -> AtXY(Num.combine(p.x, Num.of(1.0)) { a, b -> a + b }, Num.combine(p.y, m) { a, b -> a + b })
                else -> throw SpecError("point and slope needs coordinates")
            }
        }

        private fun lineRef(raw: Any?): LineRef = when (raw) {
            is String -> {
                if (kinds[raw.trim()] != Kind.LINE) throw SpecError("'$raw' is not a named line")
                NamedLine(raw.trim())
            }
            else -> twoPoints(raw).let { (a, b) -> TwoPoints(a, b) }
        }

        private fun vector(raw: Any?): Pair<Point2, Point2> = when {
            raw is JSONArray && raw.length() == 2 && (raw.opt(0) is JSONArray || raw.opt(0) is String) -> twoPoints(raw)
            raw is JSONObject -> (raw.opt("from")?.let { point(it) } ?: origin()) to point(raw.opt("to") ?: throw SpecError("vector needs to"))
            else -> origin() to point(raw)
        }

        fun point3(raw: Any?): Point3 = when (raw) {
            is JSONArray -> {
                if (raw.length() < 3) throw SpecError("a point is [x, y, z]")
                AtXYZ(num(raw.opt(0)), num(raw.opt(1)), num(raw.opt(2)))
            }
            is String -> {
                val name = raw.trim()
                if (kinds[name] != Kind.POINT) throw SpecError("'$name' is not a named point")
                Point3Ref(name)
            }
            is JSONObject -> when {
                raw.has("midpoint") -> twoPoints3(raw.opt("midpoint")).let { (a, b) -> Midpoint3(a, b) }
                // A point item written in place of a point: {"point": [1, 2, 3]}.
                raw.has("point") -> point3(raw.opt("point"))
                raw.has("at") -> point3(raw.opt("at"))
                raw.has("x") -> AtXYZ(num(raw.opt("x")), num(raw.opt("y")), num(raw.opt("z")))
                else -> throw SpecError("a point is [x, y, z], a name, or {midpoint}")
            }
            else -> throw SpecError("a point is [x, y, z]")
        }

        private fun twoPoints3(raw: Any?): Pair<Point3, Point3> = when {
            raw is JSONObject -> point3(raw.opt("from")) to point3(raw.opt("to"))
            raw is JSONArray && raw.length() == 2 -> point3(raw.opt(0)) to point3(raw.opt(1))
            else -> throw SpecError("use two points [[x1, y1, z1], [x2, y2, z2]]")
        }

        private fun plane(raw: Any?): PlaneDef = when (raw) {
            is String -> {
                val sides = raw.replace("==", "=").split("=")
                if (sides.size > 2) throw SpecError("a plane is one equation such as x + y + z = 3")
                val text = if (sides.size == 2) "(${sides[0]})-(${sides[1]})" else sides[0]
                PlaneEquation(formula(text, listOf("x", "y", "z")))
            }
            is JSONObject -> when {
                raw.has("through") -> {
                    val arr = raw.optJSONArray("through") ?: throw SpecError("through takes three points")
                    if (arr.length() != 3) throw SpecError("a plane through points needs exactly three")
                    PlaneThroughPoints(point3(arr.opt(0)), point3(arr.opt(1)), point3(arr.opt(2)))
                }
                raw.has("normal") -> PlanePointNormal(
                    raw.opt("point")?.let { point3(it) },
                    point3(raw.opt("normal")),
                    raw.opt("d")?.let { num(it) }
                )
                else -> throw SpecError("a plane needs an equation, a normal, or three points")
            }
            is JSONArray -> {
                if (raw.length() != 3) throw SpecError("a plane through points needs exactly three")
                PlaneThroughPoints(point3(raw.opt(0)), point3(raw.opt(1)), point3(raw.opt(2)))
            }
            else -> throw SpecError("a plane is an equation such as x + y + z = 3")
        }

        /** The named solids, scaled to circumradius r around a center. */
        private fun preset(name: String, o: JSONObject): PolyhedronItem {
            val solid = Solids.named(name) ?: throw SpecError("unknown polyhedron '$name'")
            val center = o.opt("center")?.let { point3(it) } ?: origin3()
            val r = num(o.opt("r") ?: o.opt("radius") ?: o.opt("size") ?: 1)
            val vertices = solid.vertices.map { v ->
                fun axis(c: Num, k: Int) = Num.combine(c, r) { cv, rv -> cv + v[k] * rv }
                when (center) {
                    is AtXYZ -> AtXYZ(axis(center.x, 0), axis(center.y, 1), axis(center.z, 2))
                    else -> throw SpecError("a preset's center must be coordinates")
                }
            }
            return PolyhedronItem(vertices, solid.faces, style(o))
        }

        private fun origin() = AtXY(Num.of(0.0), Num.of(0.0))
        private fun origin3() = AtXYZ(Num.of(0.0), Num.of(0.0), Num.of(0.0))
        private fun turn() = Span(Num.of(0.0), Num.of(2 * Math.PI))
        private fun half(n: Num) = Num.combine(n, Num.of(0.0)) { a, _ -> a / 2 }

        private fun sum3(a: Point3, b: Point3): Point3 = when {
            a is AtXYZ && b is AtXYZ -> AtXYZ(
                Num.combine(a.x, b.x) { p, q -> p + q }, Num.combine(a.y, b.y) { p, q -> p + q }, Num.combine(a.z, b.z) { p, q -> p + q }
            )
            else -> throw SpecError("use coordinates here, not a name")
        }

        private fun diff3(a: Point3, b: Point3): Point3 = when {
            a is AtXYZ && b is AtXYZ -> AtXYZ(
                Num.combine(a.x, b.x) { p, q -> p - q }, Num.combine(a.y, b.y) { p, q -> p - q }, Num.combine(a.z, b.z) { p, q -> p - q }
            )
            else -> throw SpecError("use coordinates here, not a name")
        }

        private fun string(raw: Any?): String = when (raw) {
            is String -> raw.trim().ifEmpty { throw SpecError("an expression is empty") }
            is Number -> raw.toString()
            else -> throw SpecError("expected text")
        }

        private fun strings(raw: Any?, count: Int): List<String> {
            val arr = raw as? JSONArray ?: throw SpecError("expected $count formulas")
            if (arr.length() != count) throw SpecError("expected $count formulas")
            return (0 until count).map { string(arr.opt(it)) }
        }

        /**
         * Splits "x^2 + y^2 <= 4" into "(x^2 + y^2)-(4)" and the relation. A condition
         * joined with and/or, or a chain such as "0 <= y <= 1", stays whole: it shades
         * where it holds.
         */
        private fun relation(text: String): Pair<String, Relation> {
            if (LOGIC.containsMatchIn(text) || topLevelComparisons(text) > 1) return text to Relation.HOLDS
            var depth = 0
            var i = 0
            while (i < text.length) {
                val c = text[i]
                when (c) {
                    '(', '{', '[' -> depth++
                    ')', '}', ']' -> depth--
                    '<', '>', '=', '≤', '≥' -> if (depth == 0) {
                        val two = text.substring(i, minOf(i + 2, text.length))
                        val (relation, width) = when {
                            two == "<=" || c == '≤' -> Relation.LE to if (c == '≤') 1 else 2
                            two == ">=" || c == '≥' -> Relation.GE to if (c == '≥') 1 else 2
                            two == "==" -> Relation.EQ to 2
                            c == '<' -> Relation.LT to 1
                            c == '>' -> Relation.GT to 1
                            else -> Relation.EQ to 1
                        }
                        val lhs = text.substring(0, i)
                        val rhs = text.substring(i + width)
                        if (lhs.isBlank() || rhs.isBlank()) throw SpecError("one side of '$text' is empty")
                        return "($lhs)-($rhs)" to relation
                    }
                }
                i++
            }
            return text to Relation.EQ
        }

        /** Comparison operators outside brackets; "<=" and "!=" count once. */
        private fun topLevelComparisons(text: String): Int {
            var depth = 0
            var count = 0
            var i = 0
            while (i < text.length) {
                when (text[i]) {
                    '(', '{', '[' -> depth++
                    ')', '}', ']' -> depth--
                    '<', '>', '=', '≤', '≥', '≠' -> if (depth == 0) {
                        count++
                        // Skip the second character of <=, >=, ==.
                        if (text.getOrNull(i + 1) == '=') i++
                    }
                    '!' -> if (depth == 0 && text.getOrNull(i + 1) == '=') {
                        count++
                        i++
                    }
                }
                i++
            }
            return count
        }

        private fun style(o: JSONObject): Style {
            val label = o.opt("label")?.takeIf { it != JSONObject.NULL }?.toString()?.trim()?.takeIf { it.isNotEmpty() }
            val dash = when (o.optString("style").lowercase()) {
                "dashed", "dash" -> Dash.DASHED
                "dotted", "dot" -> Dash.DOTTED
                else -> if (o.optBoolean("dashed")) Dash.DASHED else Dash.SOLID
            }
            val color = parseColor(o.opt("color"))
            val opacity = o.optDouble("opacity", Double.NaN).takeIf { !it.isNaN() }?.coerceIn(0.0, 1.0)
            val fill = when (val f = o.opt("fill")) {
                is String -> parseColor(f)
                true -> color ?: 0
                else -> null
            }?.let { c -> if (opacity != null) withAlpha(c, opacity) else c }
            return Style(
                label = label,
                color = color,
                dash = dash,
                fill = fill ?: opacity?.let { withAlpha(color ?: 0, it) },
                width = o.optDouble("width", Double.NaN).takeIf { !it.isNaN() }?.toFloat()?.coerceIn(0.5f, 8f),
                visible = !o.optBoolean("hidden", false) && o.optBoolean("visible", true)
            )
        }
    }

    /** [argb] with its alpha set to [opacity] (0..1). A colour of 0 means "the item's colour". */
    private fun withAlpha(argb: Int, opacity: Double): Int =
        ((opacity * 255).toInt().coerceIn(0, 255) shl 24) or (argb and 0xFFFFFF)

    // ------------------------------------------------------------------ colours

    /** Named colours map to the chart palette; `#RGB`, `#RRGGBB` and `#AARRGGBB` work too. */
    fun parseColor(raw: Any?): Int? {
        val text = (raw as? String)?.trim()?.lowercase() ?: return null
        NAMED_COLORS[text]?.let { return it }
        if (!text.startsWith("#")) return null
        val hex = text.removePrefix("#")
        return when (hex.length) {
            3 -> hex.toLongOrNull(16)?.let { v ->
                val r = (v shr 8 and 0xF) * 17
                val g = (v shr 4 and 0xF) * 17
                val b = (v and 0xF) * 17
                (0xFF000000 or (r shl 16) or (g shl 8) or b).toInt()
            }
            6 -> hex.toLongOrNull(16)?.let { (0xFF000000 or it).toInt() }
            8 -> hex.toLongOrNull(16)?.toInt()
            else -> null
        }
    }

    /** Readable on the dark answer sheet; the order is also the default series order. */
    val PALETTE: List<Int> = listOf(
        0xFF8AB4F8, 0xFFF28B82, 0xFF81C995, 0xFFFDD663, 0xFFC58AF9, 0xFF78D9EC, 0xFFFCAD70, 0xFFFF8BCB
    ).map { it.toInt() }

    private val NAMED_COLORS: Map<String, Int> = mapOf(
        "blue" to PALETTE[0], "red" to PALETTE[1], "green" to PALETTE[2], "yellow" to PALETTE[3],
        "purple" to PALETTE[4], "violet" to PALETTE[4], "cyan" to PALETTE[5], "teal" to PALETTE[5],
        "orange" to PALETTE[6], "pink" to PALETTE[7], "magenta" to PALETTE[7],
        "gray" to 0xFFBDC1C6.toInt(), "grey" to 0xFFBDC1C6.toInt(), "white" to 0xFFE8EAED.toInt(),
        "black" to 0xFF5F6368.toInt(), "brown" to 0xFFC5A47E.toInt()
    )

    // ---------------------------------------------------------------------- JSON

    /**
     * Turns what models write into strict JSON: the outermost `{…}`, without comments or
     * trailing commas, with single-quoted strings and bare keys quoted, and with LaTeX
     * commands inside strings (`"\theta"`, `"\frac"`) kept literal instead of becoming
     * JSON escapes such as a tab. Misplaced brackets are repaired: a key written inside a
     * list (`[[0, 0], [1, 1], "label": "x"]`) closes the list first, a stray `]` inside an
     * object is dropped, and lists or objects left open are closed.
     */
    internal fun cleanJson(source: String): String {
        val start = source.indexOf('{')
        val end = source.lastIndexOf('}')
        if (start < 0 || end <= start) throw SpecError("The spec is not a JSON object")
        val s = source.substring(start, end + 1)
        val out = StringBuilder(s.length + 16)
        val stack = ArrayDeque<Char>()
        var expectKey = false
        var i = 0
        while (i < s.length) {
            val c = s[i]
            when {
                c == '"' || c == '\'' -> {
                    val at = out.length
                    i = copyString(s, i, out)
                    if (stack.lastOrNull() == '[' && nextNonSpace(s, i) == ':') {
                        // A key inside a list: close the list before the comma that precedes it.
                        var p = at
                        while (p > 0 && out[p - 1].isWhitespace()) p--
                        out.insert(if (p > 0 && out[p - 1] == ',') p - 1 else at, ']')
                        stack.removeLast()
                    }
                    expectKey = false
                    continue
                }
                c == '/' && s.getOrNull(i + 1) == '/' -> {
                    while (i < s.length && s[i] != '\n') i++
                    continue
                }
                c == '/' && s.getOrNull(i + 1) == '*' -> {
                    val close = s.indexOf("*/", i + 2)
                    i = if (close < 0) s.length else close + 2
                    continue
                }
                c == '{' || c == '[' -> {
                    stack.addLast(c)
                    expectKey = c == '{'
                    out.append(c)
                }
                c == ']' && stack.lastOrNull() == '{' -> {
                    // A stray list close inside an object.
                    i++
                    continue
                }
                c == '}' || c == ']' -> {
                    // Lists still open when their object closes.
                    if (c == '}') while (stack.lastOrNull() == '[') {
                        out.append(']')
                        stack.removeLast()
                    }
                    if (stack.isNotEmpty()) stack.removeLast()
                    expectKey = false
                    out.append(c)
                }
                c == ',' -> {
                    // Drop a trailing comma before a closing bracket.
                    var j = i + 1
                    while (j < s.length && s[j].isWhitespace()) j++
                    if (j < s.length && (s[j] == '}' || s[j] == ']')) {
                        i++
                        continue
                    }
                    expectKey = stack.lastOrNull() == '{'
                    out.append(c)
                }
                expectKey && (c.isLetter() || c == '_') -> {
                    // A bare key: quote it.
                    var j = i
                    while (j < s.length && (s[j].isLetterOrDigit() || s[j] == '_')) j++
                    out.append('"').append(s, i, j).append('"')
                    expectKey = false
                    i = j
                    continue
                }
                else -> {
                    if (!c.isWhitespace()) expectKey = false
                    out.append(c)
                }
            }
            i++
        }
        // Whatever is still open, as at the end of a spec cut short.
        while (stack.isNotEmpty()) out.append(if (stack.removeLast() == '{') '}' else ']')
        return out.toString()
    }

    private fun nextNonSpace(s: String, from: Int): Char? {
        var j = from
        while (j < s.length && s[j].isWhitespace()) j++
        return s.getOrNull(j)
    }

    /** Copies the string literal starting at [start] as a double-quoted JSON string. */
    private fun copyString(s: String, start: Int, out: StringBuilder): Int {
        val quote = s[start]
        out.append('"')
        var i = start + 1
        while (i < s.length) {
            val c = s[i]
            when {
                c == quote -> {
                    out.append('"')
                    return i + 1
                }
                c == '\\' && i + 1 < s.length -> {
                    val n = s[i + 1]
                    val after = s.getOrNull(i + 2)
                    when {
                        n == '\\' || n == '/' -> out.append('\\').append(n)
                        n == quote && quote == '\'' -> out.append('\'')
                        n == '"' -> out.append("\\\"")
                        n == 'u' && s.length >= i + 6 && s.substring(i + 2, i + 6).toIntOrNull(16) != null -> out.append("\\u")
                        // \n, \t, … alone are JSON escapes; \nu, \theta, \frac are LaTeX.
                        n in "bfnrt" && (after == null || !after.isLetter()) -> out.append('\\').append(n)
                        else -> out.append("\\\\").append(n)
                    }
                    i += 2
                    continue
                }
                c == '"' -> out.append("\\\"")
                c == '\n' -> out.append("\\n")
                c == '\t' -> out.append("\\t")
                else -> out.append(c)
            }
            i++
        }
        throw SpecError("A text value is not closed")
    }
}

/** Vertices (circumradius 1) and faces of the named solids, built from their geometry. */
object Solids {

    class Solid(val vertices: List<DoubleArray>, val faces: List<IntArray>)

    fun named(name: String): Solid? = when (name.trim().lowercase()) {
        "tetrahedron" -> fromTriangles(normalise(listOf(
            doubleArrayOf(1.0, 1.0, 1.0), doubleArrayOf(1.0, -1.0, -1.0), doubleArrayOf(-1.0, 1.0, -1.0), doubleArrayOf(-1.0, -1.0, 1.0)
        )))
        "cube", "hexahedron" -> cube()
        "octahedron" -> fromTriangles(normalise(listOf(
            doubleArrayOf(1.0, 0.0, 0.0), doubleArrayOf(-1.0, 0.0, 0.0), doubleArrayOf(0.0, 1.0, 0.0),
            doubleArrayOf(0.0, -1.0, 0.0), doubleArrayOf(0.0, 0.0, 1.0), doubleArrayOf(0.0, 0.0, -1.0)
        )))
        "icosahedron" -> icosahedron()
        "dodecahedron" -> dodecahedron()
        "pyramid", "square pyramid" -> Solid(
            listOf(
                doubleArrayOf(-0.7, -0.7, -0.5), doubleArrayOf(0.7, -0.7, -0.5), doubleArrayOf(0.7, 0.7, -0.5),
                doubleArrayOf(-0.7, 0.7, -0.5), doubleArrayOf(0.0, 0.0, 0.9)
            ),
            listOf(intArrayOf(0, 1, 2, 3), intArrayOf(0, 1, 4), intArrayOf(1, 2, 4), intArrayOf(2, 3, 4), intArrayOf(3, 0, 4))
        )
        "prism", "triangular prism" -> {
            val ring = (0 until 3).map { k -> 2 * Math.PI * k / 3 + Math.PI / 2 }
            val bottom = ring.map { a -> doubleArrayOf(0.8 * kotlin.math.cos(a), 0.8 * kotlin.math.sin(a), -0.6) }
            val top = ring.map { a -> doubleArrayOf(0.8 * kotlin.math.cos(a), 0.8 * kotlin.math.sin(a), 0.6) }
            Solid(
                bottom + top,
                listOf(intArrayOf(0, 1, 2), intArrayOf(3, 4, 5), intArrayOf(0, 1, 4, 3), intArrayOf(1, 2, 5, 4), intArrayOf(2, 0, 3, 5))
            )
        }
        else -> null
    }

    private fun cube(): Solid {
        val s = 1 / kotlin.math.sqrt(3.0)
        val v = listOf(
            doubleArrayOf(-s, -s, -s), doubleArrayOf(s, -s, -s), doubleArrayOf(s, s, -s), doubleArrayOf(-s, s, -s),
            doubleArrayOf(-s, -s, s), doubleArrayOf(s, -s, s), doubleArrayOf(s, s, s), doubleArrayOf(-s, s, s)
        )
        val f = listOf(
            intArrayOf(0, 1, 2, 3), intArrayOf(4, 5, 6, 7), intArrayOf(0, 1, 5, 4),
            intArrayOf(1, 2, 6, 5), intArrayOf(2, 3, 7, 6), intArrayOf(3, 0, 4, 7)
        )
        return Solid(v, f)
    }

    private val PHI = (1 + kotlin.math.sqrt(5.0)) / 2

    private fun icosahedron(): Solid {
        val p = PHI
        val raw = mutableListOf<DoubleArray>()
        for (a in listOf(-1.0, 1.0)) for (b in listOf(-p, p)) {
            raw += doubleArrayOf(0.0, a, b)
            raw += doubleArrayOf(a, b, 0.0)
            raw += doubleArrayOf(b, 0.0, a)
        }
        return fromTriangles(normalise(raw))
    }

    /** The dual of the icosahedron: one vertex per icosahedron face, one face per vertex. */
    private fun dodecahedron(): Solid {
        val ico = icosahedron()
        val centres = normalise(ico.faces.map { f ->
            doubleArrayOf(
                f.sumOf { ico.vertices[it][0] } / 3, f.sumOf { ico.vertices[it][1] } / 3, f.sumOf { ico.vertices[it][2] } / 3
            )
        })
        val faces = ico.vertices.map { axis ->
            // The five faces around this vertex, ordered by angle about it.
            val around = ico.faces.indices.filter { fi -> ico.faces[fi].any { ico.vertices[it] === axis } }
            val u = perpendicular(axis)
            val w = cross(axis, u)
            around.sortedBy { fi ->
                val c = centres[fi]
                kotlin.math.atan2(dot(c, w), dot(c, u))
            }.toIntArray()
        }
        return Solid(centres, faces)
    }

    /** Faces of a convex solid whose faces are all triangles: vertex triples at edge length. */
    private fun fromTriangles(v: List<DoubleArray>): Solid {
        val edge = (1 until v.size).minOf { dist(v[0], v[it]) }
        val faces = mutableListOf<IntArray>()
        for (a in v.indices) for (b in a + 1 until v.size) for (c in b + 1 until v.size) {
            if (near(dist(v[a], v[b]), edge) && near(dist(v[b], v[c]), edge) && near(dist(v[a], v[c]), edge)) {
                faces += intArrayOf(a, b, c)
            }
        }
        return Solid(v, faces)
    }

    private fun normalise(v: List<DoubleArray>) = v.map { p ->
        val l = kotlin.math.sqrt(dot(p, p))
        doubleArrayOf(p[0] / l, p[1] / l, p[2] / l)
    }

    private fun near(a: Double, b: Double) = kotlin.math.abs(a - b) < 1e-6
    private fun dist(a: DoubleArray, b: DoubleArray) = kotlin.math.sqrt((a[0] - b[0]).let { it * it } + (a[1] - b[1]).let { it * it } + (a[2] - b[2]).let { it * it })
    private fun dot(a: DoubleArray, b: DoubleArray) = a[0] * b[0] + a[1] * b[1] + a[2] * b[2]
    private fun cross(a: DoubleArray, b: DoubleArray) = doubleArrayOf(a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0])

    private fun perpendicular(a: DoubleArray): DoubleArray {
        val helper = if (kotlin.math.abs(a[0]) < 0.9) doubleArrayOf(1.0, 0.0, 0.0) else doubleArrayOf(0.0, 1.0, 0.0)
        val c = cross(a, helper)
        val l = kotlin.math.sqrt(dot(c, c))
        return doubleArrayOf(c[0] / l, c[1] / l, c[2] / l)
    }
}
