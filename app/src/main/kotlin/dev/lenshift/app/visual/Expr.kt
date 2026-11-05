package dev.lenshift.app.visual

import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.acosh
import kotlin.math.asin
import kotlin.math.asinh
import kotlin.math.atan
import kotlin.math.atan2
import kotlin.math.atanh
import kotlin.math.cbrt
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.cosh
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.log2
import kotlin.math.pow
import kotlin.math.round
import kotlin.math.sign
import kotlin.math.sin
import kotlin.math.sinh
import kotlin.math.sqrt
import kotlin.math.tan
import kotlin.math.tanh

/**
 * A math expression compiled from text such as `x^2 - 2x + sin(3t)`.
 *
 * Graph specs are written by a model, so this is a calculator, not a language: numbers,
 * named variables, `+ - * / ^`, comparisons and a fixed set of functions. Text is first
 * normalised (Unicode operators, simple LaTeX), implicit multiplication is accepted (`2x`,
 * `3sin(x)`, `(x+1)(x-1)`, `xy`), and size limits keep a hostile or confused spec from
 * hanging the app.
 */
class Expr private constructor(private val root: Node, val source: String, val usesVariables: Boolean) {

    /** Evaluates with [values] in the order of the variable names given to [parse]. */
    fun eval(values: DoubleArray): Double = root.eval(values)

    companion object {
        const val MAX_LENGTH = 1_000

        /**
         * Parses [text], allowing [variables] besides constants and built-in functions, and
         * calls to the spec's own one-variable [functions] (`f(x)`, and `f'(x)` for the
         * derivative). Variable names may be dotted, like the point coordinate `A.x`. When a
         * name appears twice, the first wins: a curve's own `t` shadows a slider named t.
         * @throws ExprError with a message fit to show the user.
         */
        fun parse(
            text: String,
            variables: List<String> = emptyList(),
            functions: Map<String, UserFunction> = emptyMap()
        ): Expr {
            if (text.length > MAX_LENGTH) throw ExprError("Expression is too long")
            val normalised = normalise(text)
            if (normalised.isBlank()) throw ExprError("Empty expression")
            val slots = HashMap<String, Int>()
            variables.forEachIndexed { i, name -> slots.putIfAbsent(name, i) }
            val tokens = Tokenizer(normalised, slots.keys, functions.keys).tokens()
            val parser = Parser(tokens, slots, variables.size, functions)
            val root = parser.parseAll()
            return Expr(root, text.trim(), parser.usedVariable)
        }

        /** Evaluates a constant such as `2pi` or `sqrt(2)/2`. */
        fun constant(text: String): Double = parse(text).eval(DoubleArray(0))

        /** True for names of constants, functions and words, which variables may not reuse. */
        fun isReserved(name: String): Boolean =
            name in CONSTANTS || name in FUNCTIONS || name == "frac" || name.lowercase() in LOGIC_WORDS

        /**
         * Rewrites Unicode and LaTeX notation models often use into plain syntax:
         * `−` `×` `·` `÷` `²` `³` `π` `θ` `√` `≤` `≥` `≠` `∧` `∨` `¬`, `\frac{a}{b}`, `\sin`,
         * `\cdot`, `\le`, `\land`, braces.
         */
        internal fun normalise(text: String): String {
            var s = text.trim()
            s = s.replace("\\left", "").replace("\\right", "")
                .replace("\\cdot", "*").replace("\\times", "*").replace("\\div", "/")
            for ((command, plain) in LATEX_WORDS) s = command.replace(s, plain)
            s = s.replace(Regex("""\\(?=[A-Za-z])"""), "")
            val out = StringBuilder(s.length + 8)
            for (ch in s) {
                when (ch) {
                    '−', '–', '—' -> out.append('-')
                    '×', '·', '⋅', '∙', '•' -> out.append('*')
                    '÷' -> out.append('/')
                    '²' -> out.append("^2")
                    '³' -> out.append("^3")
                    'π' -> out.append(" pi ")
                    'θ' -> out.append(" theta ")
                    'τ' -> out.append(" tau ")
                    '√' -> out.append(" sqrt ")
                    '≤' -> out.append("<=")
                    '≥' -> out.append(">=")
                    '≠' -> out.append("!=")
                    '∧' -> out.append(" and ")
                    '∨' -> out.append(" or ")
                    '¬' -> out.append(" not ")
                    '{' -> out.append('(')
                    '}' -> out.append(')')
                    else -> out.append(ch)
                }
            }
            return out.toString().replace("**", "^")
        }

        /** Words that join or negate conditions: `y > x^2 and y < 2`. */
        internal val LOGIC_WORDS = setOf("and", "or", "not")

        private val LATEX_WORDS: List<Pair<Regex, String>> = listOf(
            Regex("""\\(?:leqslant|leq|le)(?![A-Za-z])""") to "<=",
            Regex("""\\(?:geqslant|geq|ge)(?![A-Za-z])""") to ">=",
            Regex("""\\(?:neq|ne)(?![A-Za-z])""") to "!=",
            Regex("""\\(?:land|wedge)(?![A-Za-z])""") to " and ",
            Regex("""\\(?:lor|vee)(?![A-Za-z])""") to " or ",
            Regex("""\\(?:lnot|neg)(?![A-Za-z])""") to " not "
        )
    }
}

class ExprError(message: String) : IllegalArgumentException(message)

/**
 * A named function of one variable defined by the spec (`{"fn": "x^2", "name": "f"}`), so
 * other expressions can call `f(a)` or `f'(a)`. [expr] reads (x, shared…), where the
 * [sharedCount] shared values (sliders, point coordinates) are the last values of every
 * expression in the same spec.
 */
class UserFunction(val expr: Expr, val sharedCount: Int)

// ============================================================================ tokens

private sealed interface Token {
    data class Num(val value: Double) : Token
    data class Name(val name: String) : Token
    data class Op(val op: String) : Token
}

private class Tokenizer(
    private val text: String,
    private val variables: Set<String>,
    private val userFunctions: Set<String>
) {

    fun tokens(): List<Token> {
        val out = mutableListOf<Token>()
        var i = 0
        while (i < text.length) {
            val c = text[i]
            when {
                c.isWhitespace() -> i++
                c.isDigit() || (c == '.' && i + 1 < text.length && text[i + 1].isDigit()) -> {
                    val start = i
                    while (i < text.length && (text[i].isDigit() || text[i] == '.')) i++
                    // An exponent only when digits follow, so "2e" stays 2·e.
                    if (i < text.length && (text[i] == 'e' || text[i] == 'E')) {
                        val next = text.getOrNull(i + 1)
                        val afterSign = text.getOrNull(i + 2)
                        if (next?.isDigit() == true || ((next == '+' || next == '-') && afterSign?.isDigit() == true)) {
                            i += 2
                            while (i < text.length && text[i].isDigit()) i++
                        }
                    }
                    val literal = text.substring(start, i)
                    val value = literal.toDoubleOrNull() ?: throw ExprError("Bad number '$literal'")
                    out += Token.Num(value)
                }
                c.isLetter() -> {
                    val start = i
                    while (i < text.length && (text[i].isLetterOrDigit() || text[i] == '_')) i++
                    // A point coordinate: "A.x", "P1.y".
                    if (i + 1 < text.length && text[i] == '.' && text[i + 1] in "xyz" &&
                        (i + 2 == text.length || !text[i + 2].isLetterOrDigit())
                    ) {
                        val dotted = text.substring(start, i + 2)
                        if (dotted !in variables) throw ExprError("Unknown point '${text.substring(start, i)}'")
                        out += Token.Name(dotted)
                        i += 2
                    } else {
                        val word = text.substring(start, i)
                        val lower = word.lowercase()
                        if (lower in Expr.LOGIC_WORDS) out += Token.Op(lower) else out += names(word)
                    }
                }
                else -> {
                    val two = text.substring(i, minOf(i + 2, text.length))
                    if (two in TWO_CHAR) {
                        out += Token.Op(two)
                        i += 2
                    } else if (c in SINGLE) {
                        out += Token.Op(c.toString())
                        i++
                    } else {
                        throw ExprError("Unexpected '$c'")
                    }
                }
            }
        }
        return out
    }

    /** A known name, or an unknown run of letters split into known ones ("xy" → x·y). */
    private fun names(word: String): List<Token> {
        val lower = word.lowercase()
        if (known(word)) return listOf(Token.Name(word))
        if (word != lower && known(lower)) return listOf(Token.Name(lower))
        split(word)?.let { parts -> return parts.map { Token.Name(it) } }
        throw ExprError("Unknown name '$word'")
    }

    private fun known(name: String) =
        name in variables || name in userFunctions || name in CONSTANTS || name in FUNCTIONS || name == "frac"

    /** Splits [word] into known names, longest first; null when it cannot be done. */
    private fun split(word: String): List<String>? {
        if (word.isEmpty()) return emptyList()
        if (word.length > 24) return null
        val candidates = (variables + userFunctions + CONSTANTS.keys + FUNCTIONS.keys)
            .filter { word.startsWith(it) }
            .sortedByDescending { it.length }
        for (name in candidates) {
            val rest = split(word.substring(name.length)) ?: continue
            return listOf(name) + rest
        }
        return null
    }

    companion object {
        val TWO_CHAR = setOf("<=", ">=", "==", "!=", "&&")
        val SINGLE = setOf('+', '-', '*', '/', '^', '(', ')', ',', '<', '>', '|', '!', '\'')
    }
}

// ============================================================================ parser

private class Parser(
    private val tokens: List<Token>,
    private val slots: Map<String, Int>,
    /** How many values the expression is evaluated with (duplicate names included). */
    private val valueCount: Int,
    private val userFunctions: Map<String, UserFunction>
) {
    private var pos = 0
    private var depth = 0
    private var nodes = 0
    var usedVariable = false
        private set

    fun parseAll(): Node {
        val node = logicOr()
        if (pos < tokens.size) throw ExprError("Unexpected ${describe(tokens[pos])}")
        return node
    }

    private fun peek(): Token? = tokens.getOrNull(pos)
    private fun isOp(op: String) = (peek() as? Token.Op)?.op == op
    private fun next(): Token = tokens.getOrNull(pos++) ?: throw ExprError("Expression ends too early")

    private fun expect(op: String) {
        val t = next()
        if ((t as? Token.Op)?.op != op) throw ExprError("Expected '$op' but found ${describe(t)}")
    }

    private fun <T : Node> count(node: T): T {
        if (++nodes > MAX_NODES) throw ExprError("Expression is too large")
        return node
    }

    private fun logicOr(): Node {
        var node = logicAnd()
        while (isOp("or")) {
            pos++
            node = count(Node.Or(node, logicAnd()))
        }
        return node
    }

    private fun logicAnd(): Node {
        var node = logicNot()
        while (isOp("and") || isOp("&&")) {
            pos++
            node = count(Node.And(node, logicNot()))
        }
        return node
    }

    /** `not` or a leading `!`; after an operand `!` is a factorial instead. */
    private fun logicNot(): Node {
        if (isOp("not") || isOp("!")) {
            pos++
            return count(Node.Not(logicNot()))
        }
        return comparison()
    }

    /** One comparison, or a chain such as `0 <= y <= 1`, which holds when every link does. */
    private fun comparison(): Node {
        var left = additive()
        var chain: Node? = null
        while (true) {
            val op = (peek() as? Token.Op)?.op
            if (op == null || op !in COMPARISONS) break
            pos++
            val right = additive()
            val link = count(Node.Compare(op, left, right))
            chain = if (chain == null) link else count(Node.And(chain, link))
            left = right
        }
        return chain ?: left
    }

    private fun additive(): Node {
        var node = multiplicative()
        while (true) {
            node = when {
                isOp("+") -> { pos++; count(Node.Add(node, multiplicative())) }
                isOp("-") -> { pos++; count(Node.Sub(node, multiplicative())) }
                else -> return node
            }
        }
    }

    private fun multiplicative(): Node {
        var node = unary()
        while (true) {
            node = when {
                isOp("*") -> { pos++; count(Node.Mul(node, unary())) }
                isOp("/") -> { pos++; count(Node.Div(node, unary())) }
                // Juxtaposition: "2x", "3sin(x)", "(x+1)(x-1)". Binds like *, but the right
                // side takes no sign of its own ("2 -x" stays a subtraction).
                startsOperand(peek()) -> count(Node.Mul(node, power()))
                else -> return node
            }
        }
    }

    private fun startsOperand(t: Token?) = t is Token.Num || t is Token.Name || (t as? Token.Op)?.op == "("

    private fun unary(): Node = when {
        isOp("-") -> { pos++; count(Node.Neg(unary())) }
        isOp("+") -> { pos++; unary() }
        else -> power()
    }

    /** `a^b` is right-associative and binds tighter than a leading minus: -x^2 = -(x^2). */
    private fun power(): Node {
        val base = postfix()
        if (isOp("^")) {
            pos++
            return count(Node.Pow(base, unary()))
        }
        return base
    }

    private fun postfix(): Node {
        var node = primary()
        while (isOp("!")) {
            pos++
            node = count(Node.Call1(::factorial, node))
        }
        return node
    }

    private fun primary(): Node {
        if (++depth > MAX_DEPTH) throw ExprError("Expression is nested too deeply")
        try {
            return when (val t = next()) {
                is Token.Num -> count(Node.Const(t.value))
                is Token.Op -> when (t.op) {
                    "(" -> logicOr().also { expect(")") }
                    "|" -> count(Node.Call1(::abs, additive())).also { expect("|") }
                    else -> throw ExprError("Unexpected '${t.op}'")
                }
                is Token.Name -> name(t.name)
            }
        } finally {
            depth--
        }
    }

    private fun name(name: String): Node {
        slots[name]?.let { usedVariable = true; return count(Node.Var(it)) }
        CONSTANTS[name]?.let { return count(Node.Const(it)) }
        if (name == "frac") {
            // \frac{a}{b}, after braces became parentheses: frac(a)(b).
            expect("(")
            val top = logicOr()
            expect(")")
            expect("(")
            val bottom = logicOr()
            expect(")")
            return count(Node.Div(top, bottom))
        }
        userFunctions[name]?.let { return userCall(name, it) }
        val fn = FUNCTIONS[name] ?: throw ExprError("Unknown name '$name'")
        val args = if (isOp("(")) {
            pos++
            buildList {
                if (!isOp(")")) {
                    add(logicOr())
                    while (isOp(",")) {
                        pos++
                        add(logicOr())
                    }
                }
                expect(")")
            }
        } else {
            // "sin x", "sqrt 2": one tight operand without parentheses.
            listOf(power())
        }
        if (args.size < fn.minArgs || args.size > fn.maxArgs) {
            val wanted = if (fn.minArgs == fn.maxArgs) "${fn.minArgs}" else "${fn.minArgs} or more"
            throw ExprError("$name takes $wanted argument${if (fn.minArgs == 1 && fn.maxArgs == 1) "" else "s"}")
        }
        return count(fn.build(args))
    }

    /** `f(a)` or `f'(a)` for a function the spec defined. */
    private fun userCall(name: String, fn: UserFunction): Node {
        val derivative = isOp("'")
        if (derivative) pos++
        val callerArity = valueCount - fn.sharedCount
        if (callerArity < 0) throw ExprError("$name can't be used here")
        val arg = if (isOp("(")) {
            pos++
            logicOr().also { expect(")") }
        } else {
            power()
        }
        usedVariable = true
        return count(Node.UserCall(fn.expr, arg, callerArity, fn.sharedCount, derivative))
    }

    private fun describe(t: Token): String = when (t) {
        is Token.Num -> "number ${t.value}"
        is Token.Name -> "'${t.name}'"
        is Token.Op -> "'${t.op}'"
    }

    companion object {
        const val MAX_DEPTH = 60
        const val MAX_NODES = 4_000
        val COMPARISONS = setOf("<", ">", "<=", ">=", "==", "!=")
    }
}

// ============================================================================== nodes

private sealed class Node {
    abstract fun eval(v: DoubleArray): Double

    class Const(private val value: Double) : Node() {
        override fun eval(v: DoubleArray) = value
    }

    class Var(private val slot: Int) : Node() {
        override fun eval(v: DoubleArray) = v[slot]
    }

    class Neg(private val a: Node) : Node() {
        override fun eval(v: DoubleArray) = -a.eval(v)
    }

    class Add(private val a: Node, private val b: Node) : Node() {
        override fun eval(v: DoubleArray) = a.eval(v) + b.eval(v)
    }

    class Sub(private val a: Node, private val b: Node) : Node() {
        override fun eval(v: DoubleArray) = a.eval(v) - b.eval(v)
    }

    class Mul(private val a: Node, private val b: Node) : Node() {
        override fun eval(v: DoubleArray) = a.eval(v) * b.eval(v)
    }

    class Div(private val a: Node, private val b: Node) : Node() {
        override fun eval(v: DoubleArray) = a.eval(v) / b.eval(v)
    }

    class Pow(private val a: Node, private val b: Node) : Node() {
        override fun eval(v: DoubleArray): Double {
            val base = a.eval(v)
            val e = b.eval(v)
            // A negative base with an odd-denominator fraction, e.g. (-8)^(1/3) = -2.
            if (base < 0 && e != floor(e)) {
                val inverse = 1.0 / e
                if (abs(inverse - round(inverse)) < 1e-9 && round(inverse).toLong() % 2L != 0L) {
                    return -((-base).pow(e))
                }
            }
            return base.pow(e)
        }
    }

    class Compare(private val op: String, private val a: Node, private val b: Node) : Node() {
        override fun eval(v: DoubleArray): Double {
            val x = a.eval(v)
            val y = b.eval(v)
            val result = when (op) {
                "<" -> x < y
                ">" -> x > y
                "<=" -> x <= y
                ">=" -> x >= y
                "==" -> abs(x - y) <= 1e-9 * maxOf(1.0, abs(x), abs(y))
                else -> abs(x - y) > 1e-9 * maxOf(1.0, abs(x), abs(y))
            }
            return if (result) 1.0 else 0.0
        }
    }

    // Conditions are 1 or 0. Any other number counts as true unless it is 0 or undefined.
    class And(private val a: Node, private val b: Node) : Node() {
        override fun eval(v: DoubleArray) = if (truth(a.eval(v)) && truth(b.eval(v))) 1.0 else 0.0
    }

    class Or(private val a: Node, private val b: Node) : Node() {
        override fun eval(v: DoubleArray) = if (truth(a.eval(v)) || truth(b.eval(v))) 1.0 else 0.0
    }

    class Not(private val a: Node) : Node() {
        override fun eval(v: DoubleArray) = if (truth(a.eval(v))) 0.0 else 1.0
    }

    class If(private val c: Node, private val a: Node, private val b: Node) : Node() {
        override fun eval(v: DoubleArray): Double {
            val cond = c.eval(v)
            return if (cond.isNaN()) Double.NaN else if (cond != 0.0) a.eval(v) else b.eval(v)
        }
    }

    class Call1(private val f: (Double) -> Double, private val a: Node) : Node() {
        override fun eval(v: DoubleArray) = f(a.eval(v))
    }

    class Call2(private val f: (Double, Double) -> Double, private val a: Node, private val b: Node) : Node() {
        override fun eval(v: DoubleArray) = f(a.eval(v), b.eval(v))
    }

    class CallN(private val f: (DoubleArray) -> Double, private val args: List<Node>) : Node() {
        override fun eval(v: DoubleArray): Double = f(DoubleArray(args.size) { args[it].eval(v) })
    }

    /**
     * A call to a spec function: its own variable, then the shared values copied from the
     * end of the caller's values. [derivative] gives f'(a) by a central difference.
     */
    class UserCall(
        private val callee: Expr,
        private val arg: Node,
        private val callerArity: Int,
        private val shared: Int,
        private val derivative: Boolean
    ) : Node() {
        override fun eval(v: DoubleArray): Double {
            val a = arg.eval(v)
            val buf = DoubleArray(1 + shared)
            System.arraycopy(v, callerArity, buf, 1, shared)
            if (!derivative) {
                buf[0] = a
                return callee.eval(buf)
            }
            val h = 1e-5 * maxOf(1.0, abs(a))
            buf[0] = a + h
            val up = callee.eval(buf)
            buf[0] = a - h
            val down = callee.eval(buf)
            return (up - down) / (2 * h)
        }
    }
}

private class Fn(val minArgs: Int, val maxArgs: Int, val build: (List<Node>) -> Node)

private fun f1(f: (Double) -> Double) = Fn(1, 1) { Node.Call1(f, it[0]) }
private fun f2(f: (Double, Double) -> Double) = Fn(2, 2) { Node.Call2(f, it[0], it[1]) }

private val CONSTANTS: Map<String, Double> = mapOf(
    "pi" to Math.PI,
    "e" to Math.E,
    "tau" to 2 * Math.PI
)

private val FUNCTIONS: Map<String, Fn> = buildMap {
    put("sin", f1(::sin)); put("cos", f1(::cos)); put("tan", f1(::tan))
    put("sec", f1 { 1 / cos(it) }); put("csc", f1 { 1 / sin(it) }); put("cot", f1 { cos(it) / sin(it) })
    val asinFn = f1(::asin); put("asin", asinFn); put("arcsin", asinFn)
    val acosFn = f1(::acos); put("acos", acosFn); put("arccos", acosFn)
    // atan(y, x) with two arguments is atan2.
    val atanFn = Fn(1, 2) { if (it.size == 1) Node.Call1(::atan, it[0]) else Node.Call2(::atan2, it[0], it[1]) }
    put("atan", atanFn); put("arctan", atanFn)
    put("atan2", f2(::atan2))
    put("sinh", f1(::sinh)); put("cosh", f1(::cosh)); put("tanh", f1(::tanh))
    put("asinh", f1(::asinh)); put("arsinh", f1(::asinh))
    put("acosh", f1(::acosh)); put("arcosh", f1(::acosh))
    put("atanh", f1(::atanh)); put("artanh", f1(::atanh))
    put("exp", f1(::exp))
    put("ln", f1(::ln))
    // log(x) is base 10; log(x, b) is base b.
    put("log", Fn(1, 2) { if (it.size == 1) Node.Call1(::log10, it[0]) else Node.Call2({ x, b -> ln(x) / ln(b) }, it[0], it[1]) })
    put("log10", f1(::log10)); put("log2", f1(::log2))
    put("sqrt", f1(::sqrt)); put("cbrt", f1(::cbrt))
    put("abs", f1(::abs))
    val signFn = f1 { sign(it) }; put("sign", signFn); put("sgn", signFn)
    put("floor", f1(::floor)); put("ceil", f1(::ceil)); put("round", f1 { round(it) })
    put("min", Fn(1, 32) { args -> Node.CallN({ a -> a.minOrNull() ?: Double.NaN }, args) })
    put("max", Fn(1, 32) { args -> Node.CallN({ a -> a.maxOrNull() ?: Double.NaN }, args) })
    put("mod", f2 { a, b -> a - b * floor(a / b) })
    put("pow", f2 { a, b -> a.pow(b) })
    put("hypot", f2(::hypot))
    put("if", Fn(3, 3) { Node.If(it[0], it[1], it[2]) })
}

private fun truth(x: Double) = x != 0.0 && !x.isNaN()

/** n! for whole numbers up to 170 (beyond that the result overflows a double). */
private fun factorial(n: Double): Double {
    if (n < 0 || n != floor(n) || n > 170) return Double.NaN
    var result = 1.0
    var k = 2.0
    while (k <= n) {
        result *= k
        k += 1.0
    }
    return result
}
