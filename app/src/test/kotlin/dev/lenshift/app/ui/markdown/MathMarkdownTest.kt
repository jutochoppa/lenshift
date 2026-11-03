package dev.lenshift.app.ui.markdown

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MathMarkdownTest {

    private fun maths(text: String) =
        MathScanner.split(text).filterIsInstance<InlineSegment.Math>().map { it.span }

    @Test
    fun `inline and display delimiters are recognised`() {
        assertEquals(
            listOf(
                MathSpan("x^2", false),
                MathSpan("\\frac{a}{b}", true),
                MathSpan("y", false),
                MathSpan("z=1", true)
            ),
            maths("Let \$x^2\$ and \$\$\\frac{a}{b}\$\$ with \\(y\\) and \\[z=1\\].")
        )
    }

    @Test
    fun `prices are not math`() {
        assertTrue(maths("It costs \$5 and \$10 today.").isEmpty())
        assertTrue(maths("Pay \$ 3 now and \$4 later").isEmpty())
        assertEquals("costs \$5.", (MathScanner.split("costs \$5.").single() as InlineSegment.Text).text)
    }

    @Test
    fun `escaped dollars and code spans are left alone`() {
        assertTrue(maths("Use \\\$x\\\$ literally").isEmpty())
        assertTrue(maths("Run `echo \$HOME \$PATH` here").isEmpty())
    }

    @Test
    fun `unclosed math stays text while streaming`() {
        assertTrue(maths("The value \$x^2 + ").isEmpty())
        assertTrue(maths("Display \$\$\\int_0^1").isEmpty())
    }

    @Test
    fun `normalizer rewrites stacked, modular and spacing commands`() {
        assertEquals("{=}^{!}", TexNormalizer.normalize("\\overset{!}{=}"))
        assertEquals("{\\lim}_{n\\to\\infty}", TexNormalizer.normalize("\\underset{n\\to\\infty}{\\lim}"))
        assertEquals("7 \\equiv 1 \\;(\\mathrm{mod}\\;3)", TexNormalizer.normalize("7 \\equiv 1 \\pmod{3}"))
        assertEquals("7 \\equiv 1 \\;(\\mathrm{mod}\\;3)", TexNormalizer.normalize("7 \\equiv 1 \\pmod 3"))
        assertEquals("x \\;\\mathrm{mod}\\; 3", TexNormalizer.normalize("x \\bmod 3"))
        assertEquals("a \\neq b, c \\notin S", TexNormalizer.normalize("a \\not= b, c \\not\\in S"))
        assertEquals("a\\;b", TexNormalizer.normalize("a\\hspace{1em}\\phantom{x}b"))
        // \displaystyle is supported by the typesetter and must be left alone.
        assertEquals("\\displaystyle\\frac{1}{2}", TexNormalizer.normalize("\\displaystyle\\frac{1}{2}"))
    }

    @Test
    fun `unknown commands become text instead of disappearing`() {
        assertEquals("a \\,\\mathrm{frobnicate}\\, b", TexNormalizer.replaceUnknownCommand("a \\frobnicate b", "frobnicate"))
        // Only whole command names are replaced.
        assertEquals(null, TexNormalizer.replaceUnknownCommand("\\frobnicated", "frobnicate"))
        assertEquals(null, TexNormalizer.replaceUnknownCommand("a+b", "frobnicate"))
        assertEquals(null, TexNormalizer.replaceUnknownCommand("x", "bad name"))
    }

    @Test
    fun `normalizer rewrites unsupported commands`() {
        assertEquals(
            "\\int\\!\\!\\!\\int\\!\\!\\!\\int_E x\\,dV",
            TexNormalizer.normalize("\\iiint_E x\\,dV")
        )
        assertEquals("\\frac{1}{2}", TexNormalizer.normalize("\\dfrac{1}{2}"))
        assertEquals("[x]_0^1", TexNormalizer.normalize("\\Big[x\\Big]_0^1"))
        assertEquals("\\bigcup_i A_i", TexNormalizer.normalize("\\bigcup_i A_i"))
        assertEquals("\\mathrm{rank}(A)", TexNormalizer.normalize("\\operatorname{rank}(A)"))
        assertEquals("a \\Longrightarrow b", TexNormalizer.normalize("a \\implies b"))
        assertEquals(
            "\\begin{aligned}a&=b\\end{aligned}",
            TexNormalizer.normalize("\\begin{align*}a&=b\\end{align*}")
        )
        assertEquals("x=1", TexNormalizer.normalize("\\begin{equation}x=1\\end{equation}"))
    }

    @Test
    fun `bare line breaks are wrapped in a multi-line environment`() {
        assertEquals("\\begin{gather}a\\\\b\\end{gather}", TexNormalizer.wrapLines("a\\\\b"))
        assertEquals(null, TexNormalizer.wrapLines("\\begin{cases}a\\\\b\\end{cases}"))
    }

    @Test
    fun `display math blocks parse on one line, across lines, and unclosed`() {
        val blocks = MarkdownParser.parse(
            "Intro\n\$\$x=1\$\$\n\\[\n\\int_0^1 f\n\\]\nAfter\n\$\$\\sum"
        )
        assertEquals(
            listOf(
                MdBlock.Paragraph("Intro"),
                MdBlock.Math("x=1"),
                MdBlock.Math("\\int_0^1 f"),
                MdBlock.Paragraph("After"),
                MdBlock.Math("\\sum", complete = false)
            ),
            blocks
        )
    }

    @Test
    fun `bare environments become display math`() {
        val blocks = MarkdownParser.parse("\\begin{aligned}\na &= b \\\\\nc &= d\n\\end{aligned}\nDone")
        assertTrue(blocks.first() is MdBlock.Math)
        assertTrue((blocks.first() as MdBlock.Math).tex.endsWith("\\end{aligned}"))
        assertEquals(MdBlock.Paragraph("Done"), blocks.last())
    }

    @Test
    fun `tables parse with pipes inside math kept in the cell`() {
        val blocks = MarkdownParser.parse(
            "| Name | Value |\n|---|:---:|\n| abs | \$|x|\$ |\n| two | 2 |"
        )
        assertEquals(
            listOf(MdBlock.Table(listOf("Name", "Value"), listOf(listOf("abs", "\$|x|\$"), listOf("two", "2")))),
            blocks
        )
    }

    @Test
    fun `nested list items keep their level`() {
        val blocks = MarkdownParser.parse("- top\n  - child\n    - grandchild\n1. first")
        assertEquals(
            listOf(
                MdBlock.Bullet("top", 0),
                MdBlock.Bullet("child", 1),
                MdBlock.Bullet("grandchild", 2),
                MdBlock.Numbered(1, "first", 0)
            ),
            blocks
        )
    }

    @Test
    fun `a lone asterisk between spaces is not italic`() {
        val built = InlineMarkdown.build("2 * 3 * 4", androidx.compose.ui.graphics.Color.Unspecified) { null }
        assertEquals("2 * 3 * 4", built.text)
        assertFalse(built.spanStyles.any())
    }
}
