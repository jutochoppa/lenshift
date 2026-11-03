package dev.lenshift.app.visual

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class VisualSpecTest {

    private fun graph(json: String) = VisualSpec.parse(json, threeD = false) as Graph2D
    private fun scene(json: String) = VisualSpec.parse(json, threeD = true) as Scene3D

    @Test
    fun `model-style json is repaired`() {
        val cleaned = VisualSpec.cleanJson(
            """
            Here you go: {
              // a parabola
              title: 'y = x^2',
              "items": [ {fn: "x^2", label: "${'$'}\theta${'$'}",}, ],
            }
            """.trimIndent()
        )
        val json = JSONObject(cleaned)
        assertEquals("y = x^2", json.getString("title"))
        assertEquals("\$\\theta\$", json.getJSONArray("items").getJSONObject(0).getString("label"))
    }

    @Test
    fun `every 2d item kind parses`() {
        val g = graph(
            """{"title": "All", "params": {"a": {"value": 1, "min": 0, "max": 3, "animate": true}}, "items": [
              {"fn": "a*x^2", "name": "f"},
              {"fn": "x = y^2"},
              {"parametric": ["cos(t)", "sin(t)"]},
              {"polar": "1 + cos(theta)"},
              {"implicit": "x^2 + y^2 <= 4"},
              {"derivative": "f"},
              {"spline": [[0,0],[1,2],[2,1]]},
              {"point": [1, 2], "name": "A", "drag": true},
              {"point": [4, 0], "name": "B"},
              {"point": {"on": "f", "x": 1}, "name": "P", "drag": true},
              {"segment": ["A", "B"], "name": "ab", "showLength": true},
              {"line": [[0,0],[1,1]], "name": "d"},
              {"vector": [[0,0],[2,1]]},
              {"perpendicular": {"to": "ab", "through": "A"}},
              {"parallel": {"to": "d", "through": "B"}},
              {"bisector": ["A", "B", [0, 0]]},
              {"perpendicularBisector": ["A", "B"]},
              {"tangent": {"fn": "f", "at": "P"}},
              {"normal": {"fn": "x^3", "at": 1}},
              {"circle": {"center": "A", "r": 2}, "name": "c"},
              {"point": {"on": "c", "angle": 45}, "name": "Q"},
              {"point": {"intersection": ["ab", "c"], "index": 1}},
              {"circumcircle": ["A", "B", [0, 3]]},
              {"incircle": ["A", "B", [0, 3]]},
              {"arc": {"center": [0,0], "r": 1, "start": 0, "end": 90}},
              {"sector": {"center": [0,0], "from": [1,0], "to": [0,1]}},
              {"ellipse": {"center": [0,0], "rx": 3, "ry": 2, "angle": 30}},
              {"polygon": [[0,0],[3,0],[0,4]], "showArea": true},
              {"regularPolygon": {"center": [0,0], "r": 2, "n": 6}},
              {"angle": {"vertex": [0,0], "from": [3,0], "to": [0,4]}},
              {"area": {"fn": "x^2", "x": [0, 2]}},
              {"riemann": {"fn": "x^2", "x": [0, 2], "n": 4, "type": "mid"}},
              {"roots": "x^2 - 1"},
              {"extrema": "sin(x)"},
              {"intersections": ["x^2", "x + 2"]},
              {"slopefield": "x - y"},
              {"vectorfield": ["-y", "x"]},
              {"bars": [3, 5, 2], "labels": ["a", "b", "c"]},
              {"pie": [1, 2, 3]},
              {"fit": {"points": [[0,1],[1,3],[2,5]], "type": "linear"}},
              {"text": "Hello", "at": [1, 1]}
            ]}"""
        )
        assertEquals(41, g.items.size)
        assertTrue("unexpected notes: ${g.notes}", g.notes.isEmpty())
        assertTrue(g.params.single().animate)
        assertEquals(listOf("a", "A.x", "A.y", "B.x", "B.y", "P.x", "P.y", "Q.x", "Q.y"), g.shared)
        assertTrue((g.items[1] as FnItem).ofY)
        assertTrue((g.items[17] as TangentItem).point is PointRef)
    }

    @Test
    fun `bad items are skipped with a note and the rest still draws`() {
        val g = graph("""{"items": [{"fn": "x^2"}, {"fn": "2*q"}, {"wobble": 3}, {"segment": ["Z", [1, 1]]}]}""")
        assertEquals(1, g.items.size)
        assertEquals(3, g.notes.size)
        assertTrue(g.notes[0].contains("'q'"))
        assertTrue(g.notes[2].contains("named point"))
    }

    @Test
    fun `names must be defined before use and not clash`() {
        val g = graph("""{"items": [{"tangent": {"fn": "f", "at": 1}}, {"fn": "x^2", "name": "f"}, {"point": [0,0], "name": "sin"}]}""")
        // The tangent is dropped; the point is kept without its unusable name.
        assertEquals(2, g.items.size)
        assertNull((g.items[1] as PointItem).name)
        assertTrue(g.notes.any { it.contains("before it is used") })
        assertTrue(g.notes.any { it.contains("'sin' can't be used as a name") })
    }

    @Test
    fun `a single item may be the whole spec`() {
        val g = graph("""{"fn": "x^3"}""")
        assertEquals(1, g.items.size)
        assertNull(g.title)
    }

    @Test
    fun `broken specs fail with a reason`() {
        assertThrows(SpecError::class.java) { graph("not json at all") }
        assertThrows(SpecError::class.java) { graph("""{"items": []}""") }
        assertThrows(SpecError::class.java) { graph("""{"items": [{"fn": "2*q"}]}""") }
    }

    @Test
    fun `sliders reject reserved names`() {
        val g = graph("""{"params": {"x": 1, "k": {"value": 2}, "pi": 3}, "items": [{"fn": "k*x"}]}""")
        assertEquals(listOf("k"), g.params.map { it.name })
        assertEquals(2, g.notes.size)
    }

    @Test
    fun `3d items parse, including presets`() {
        val s = scene(
            """{"items": [
              {"surface": "z = x^2 - y^2"},
              {"surface": ["cos(u)*sin(v)", "sin(u)*sin(v)", "cos(v)"], "u": [0, "2pi"], "v": [0, "pi"]},
              {"curve": ["cos(t)", "sin(t)", "t/4"], "t": [0, "4pi"]},
              {"point": [1, 2, 3], "name": "P"},
              {"segment": ["P", [0, 0, 0]]},
              {"line": {"point": [0,0,0], "dir": [1,1,1]}, "name": "l"},
              {"vector": [1, 2, 2]},
              {"plane": "x + y + z = 3", "name": "p1"},
              {"plane": {"normal": [0,0,1], "point": [0,0,1]}, "name": "p2"},
              {"sphere": {"center": [0,0,0], "r": 2}, "name": "s"},
              {"box": {"center": [0,0,0], "size": 2}},
              {"cylinder": {"from": [0,0,0], "to": [0,0,2], "r": 1}},
              {"cone": {"base": [0,0,0], "apex": [0,0,2], "r": 1}},
              {"polyhedron": "icosahedron", "r": 2},
              {"polygon": [[0,0,0],[1,0,0],[0,1,0]]},
              {"circle": {"center": [0,0,0], "normal": [0,0,1], "r": 1}},
              {"intersection": ["p1", "p2"]},
              {"intersection": ["p2", "s"]},
              {"text": "hi", "at": [0,0,1]}
            ]}"""
        )
        assertTrue("unexpected notes: ${s.notes}", s.notes.isEmpty())
        assertEquals(19, s.items.size)
        val ico = s.items[13] as PolyhedronItem
        assertEquals(12, ico.vertices.size)
        assertEquals(20, ico.faces.size)
    }

    @Test
    fun `solid presets have the right shape`() {
        mapOf("tetrahedron" to (4 to 4), "cube" to (8 to 6), "octahedron" to (6 to 8), "icosahedron" to (12 to 20), "dodecahedron" to (20 to 12))
            .forEach { (name, counts) ->
                val solid = Solids.named(name)!!
                assertEquals(name, counts.first, solid.vertices.size)
                assertEquals(name, counts.second, solid.faces.size)
                solid.vertices.forEach { v -> assertEquals(name, 1.0, kotlin.math.sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2]), 1e-9) }
            }
        assertTrue(Solids.named("dodecahedron")!!.faces.all { it.size == 5 })
    }

    @Test
    fun `colors accept names and hex`() {
        assertEquals(VisualSpec.PALETTE[1], VisualSpec.parseColor("red"))
        assertEquals(0xFF112233.toInt(), VisualSpec.parseColor("#112233"))
        assertEquals(0xFFFFAA00.toInt(), VisualSpec.parseColor("#fa0"))
        assertNull(VisualSpec.parseColor("not a colour"))
    }
}
