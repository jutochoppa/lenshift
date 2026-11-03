package dev.lenshift.app.visual

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.E

class ExprTest {

    private fun eval(text: String, vararg vars: Pair<String, Double>): Double =
        Expr.parse(text, vars.map { it.first }).eval(vars.map { it.second }.toDoubleArray())

    private fun near(expected: Double, actual: Double) = assertEquals(expected, actual, 1e-9)

    @Test
    fun `operators follow maths precedence`() {
        near(14.0, eval("2+3*4"))
        near(-4.0, eval("-2^2"))
        near(512.0, eval("2^3^2"))
        near(9.0, eval("(1+2)*3"))
        near(0.5, eval("2^-1"))
        near(1.0, eval("3 - 2"))
        near(1.0, eval("2 * -3 + 7"))
    }

    @Test
    fun `juxtaposition multiplies`() {
        near(6.0, eval("2x", "x" to 3.0))
        near(3.0, eval("3sin(x)", "x" to PI / 2))
        near(8.0, eval("(x+1)(x-1)", "x" to 3.0))
        near(10.0, eval("xy", "x" to 2.0, "y" to 5.0))
        near(2 * PI, eval("2pi"))
        near(2 * E, eval("2e"))
        near(18.0, eval("2x^2", "x" to 3.0))
        near(1.0, eval("sin x^2 + cos x^2", "x" to 0.0))
    }

    @Test
    fun `unicode and latex notation are understood`() {
        near(9.0, eval("x²", "x" to 3.0))
        near(2 * PI, eval("2π"))
        near(2.0, eval("3−1"))
        near(6.0, eval("2×3"))
        near(2.0, eval("√4"))
        near(3.0, eval("√(x)", "x" to 9.0))
        near(2.0, eval("\\frac{1}{2}x", "x" to 4.0))
        near(1.0, eval("\\sin(\\pi/2)"))
        near(6.0, eval("2\\cdot 3"))
        near(4.0, eval("\\sqrt{16}"))
        near(0.5, eval("cos(θ)", "theta" to PI / 3))
    }

    @Test
    fun `functions and constants`() {
        near(1.0, eval("ln(e)"))
        near(2.0, eval("log(100)"))
        near(3.0, eval("log(8, 2)"))
        near(3.0, eval("abs(-3)"))
        near(3.0, eval("|x|", "x" to -3.0))
        near(1.0, eval("min(3, 1, 2)"))
        near(3.0, eval("max(3, 1, 2)"))
        near(2.0, eval("mod(-1, 3)"))
        near(2.0, eval("if(x < 0, -x, x)", "x" to -2.0))
        near(PI / 4, eval("atan(1, 1)"))
        near(120.0, eval("5!"))
        near(-2.0, eval("(-8)^(1/3)"))
        assertTrue(eval("sqrt(-1)").isNaN())
        near(1.0, eval("x == 2", "x" to 2.0))
    }

    @Test
    fun `mistakes give readable errors`() {
        assertThrows(ExprError::class.java) { Expr.parse("foo + 1") }
        assertThrows(ExprError::class.java) { Expr.parse("(1 + 2") }
        assertThrows(ExprError::class.java) { Expr.parse("") }
        assertThrows(ExprError::class.java) { Expr.parse("1 + ".repeat(400) + "1") }
        assertThrows(ExprError::class.java) { Expr.parse("(".repeat(80) + "1" + ")".repeat(80)) }
        assertThrows(ExprError::class.java) { Expr.parse("sin()") }
        val error = runCatching { Expr.parse("2*q", listOf("x")) }.exceptionOrNull()
        assertTrue(error?.message.orEmpty().contains("'q'"))
    }

    @Test
    fun `spec functions can be called and differentiated`() {
        val f = UserFunction(Expr.parse("x^2", listOf("x")), 0)
        val g = Expr.parse("f(3) + 1", emptyList(), mapOf("f" to f))
        near(10.0, g.eval(DoubleArray(0)))
        val slope = Expr.parse("f'(3)", emptyList(), mapOf("f" to f))
        assertEquals(6.0, slope.eval(DoubleArray(0)), 1e-5)

        // With a shared slider value a, read from the end of every caller's values.
        val h = UserFunction(Expr.parse("a*x", listOf("x", "a")), 1)
        val call = Expr.parse("h(2)", listOf("a"), mapOf("h" to h))
        near(10.0, call.eval(doubleArrayOf(5.0)))
        val inCurve = Expr.parse("h(x) + x", listOf("x", "a"), mapOf("h" to h))
        near(12.0, inCurve.eval(doubleArrayOf(2.0, 5.0)))
    }

    @Test
    fun `point coordinates are dotted names`() {
        near(3.0, eval("A.x + A.y", "A.x" to 1.0, "A.y" to 2.0))
        assertThrows(ExprError::class.java) { Expr.parse("B.x", listOf("A.x")) }
    }
}
