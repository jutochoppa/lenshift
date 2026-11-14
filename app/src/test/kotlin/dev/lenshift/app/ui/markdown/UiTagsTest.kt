package dev.lenshift.app.ui.markdown

import dev.lenshift.app.visual.FnItem
import dev.lenshift.app.visual.Graph2D
import dev.lenshift.app.visual.Resolve2D
import dev.lenshift.app.visual.View2
import dev.lenshift.app.visual.VisualSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** gpt-5.x through a ChatGPT account writes the ChatGPT app's display tags; these come from real answers. */
class UiTagsTest {

    private fun paragraphs(markdown: String) =
        MarkdownParser.parse(markdown).filterIsInstance<MdBlock.Paragraph>().map { it.text }

    @Test
    fun `a badge reads as bold text`() {
        assertEquals(
            listOf("**Piece 1** Parabola: \$y=x^2\$"),
            paragraphs("<badge color=\"info\">Piece 1</badge> Parabola: \$y=x^2\$")
        )
        assertEquals(listOf("**Actual overshoot ≈ 8.98%**"), paragraphs("<badge color=\"warning\">Actual overshoot ≈ 8.98%</badge>"))
    }

    @Test
    fun `text and caption tags keep their words`() {
        assertEquals(
            listOf("Visualizing the region:"),
            paragraphs("<text weight=\"medium\" size=\"sm\">Visualizing the region:</text>")
        )
        assertEquals(
            listOf("*Orange represents the actual PI response, which overshoots by approximately \$8.98\\%\$.*"),
            paragraphs("<caption>Orange represents the actual PI response, which overshoots by approximately \$8.98\\%\$.</caption>")
        )
    }

    @Test
    fun `a box goes and the graph inside it still draws`() {
        val blocks = MarkdownParser.parse(
            """
            |<box border radius="lg" padding={3} gap={2}>
            |  **Visual comparison**
            |  <text color="secondary" size="xs">Conceptual illustration of the two responses</text>
            |  ```graph
            |  {"title":"Step response","x":[0,3],"y":[0,1.15],"items":[{"fn":"1-exp(-4*x)"}]}
            |  ```
            |</box>
            """.trimMargin()
        )
        val code = blocks.filterIsInstance<MdBlock.Code>().single()
        assertEquals("graph", code.language)
        val text = blocks.filterIsInstance<MdBlock.Paragraph>().joinToString(" ") { it.text }
        assertEquals("**Visual comparison** Conceptual illustration of the two responses", text)
        assertFalse(text, "<" in text)
    }

    @Test
    fun `a Desmos-style Graph becomes a graph that draws`() {
        val answer = "Your region:\n\n" +
            """<Graph expressions={[{"latex":"y=x^2","restrictions":["0\\le x\\le 1"]},{"latex":"y=1","restrictions":["0\\le x\\le 1"]},{"latex":"x=0","restrictions":["0\\le y\\le 1"]}]} />""" +
            "\n\n### Step 2"
        val blocks = MarkdownParser.parse(answer)
        assertEquals(listOf("Your region:"), blocks.filterIsInstance<MdBlock.Paragraph>().map { it.text })
        val code = blocks.filterIsInstance<MdBlock.Code>().single()
        assertEquals("graph", code.language)
        assertTrue(blocks.last() is MdBlock.Heading)

        val graph = VisualSpec.parse(code.code, threeD = false) as Graph2D
        assertEquals(emptyList<String>(), graph.notes)
        val curves = graph.items.filterIsInstance<FnItem>()
        assertEquals(3, curves.size)
        assertTrue("the parabola keeps its 0 ≤ x ≤ 1 restriction", curves[0].domain != null)
        assertTrue("x = 0 is a vertical line", curves[2].ofY)
        val drawn = Resolve2D.resolve(graph, DoubleArray(0), emptyMap(), View2(-0.5, 1.5, -0.5, 1.5), 800f, 600f)
        assertEquals(emptyList<String>(), drawn.notes)
    }

    @Test
    fun `a line break in a table cell becomes a space`() {
        val table = MarkdownParser.parse("| Test | Result |\n|---|---|\n| Sum<br>check | \$-2\$<br/>passes |").single() as MdBlock.Table
        assertEquals(listOf(listOf("Sum check", "\$-2\$ passes")), table.rows)
    }

    @Test
    fun `a component that is still streaming stays hidden`() {
        val blocks = MarkdownParser.parse("Look:\n\n<Graph expressions={[{\"latex\":\"y=x")
        assertEquals(listOf(MdBlock.Paragraph("Look:")), blocks)
    }

    @Test
    fun `an unknown component is dropped`() {
        assertEquals(listOf("Before", "After"), paragraphs("Before\n\n<Chart data={[1, 2, 3]} />\n\nAfter"))
    }

    @Test
    fun `angle brackets that are not tags stay`() {
        val text = "The vector <a, b, c> has length 3 when a<b and c>d, and \$0<x<1\$; type `<badge>` to see it."
        assertEquals(listOf(text), paragraphs(text))
        assertEquals(listOf("<Enter> moves on"), paragraphs("<Enter> moves on"))
    }

    @Test
    fun `tags inside a code block are code`() {
        val code = MarkdownParser.parse("```html\n<badge color=\"info\">x</badge>\n<box>\n```").single() as MdBlock.Code
        assertEquals("<badge color=\"info\">x</badge>\n<box>", code.code)
    }

    @Test
    fun `an answer without tags is untouched`() {
        val answer = "Plain **answer** with \$x^2\$ and a table:\n\n| a | b |\n|---|---|\n| 1 | 2 |"
        assertTrue(UiTags.clean(answer) === answer)
    }
}
