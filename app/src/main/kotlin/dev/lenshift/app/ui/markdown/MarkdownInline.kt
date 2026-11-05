package dev.lenshift.app.ui.markdown

import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle

/** A piece of LaTeX found in running text. */
data class MathSpan(val tex: String, val display: Boolean)

/**
 * The text behind a typeset formula's placeholder, which a selection copies: its source
 * with delimiters, on one line. A line break inside an inline-content placeholder makes the
 * text layout spread it over several lines, and the formula image is then not drawn at all.
 */
fun mathSource(tex: String, display: Boolean): String {
    val flat = tex.trim().replace(Regex("""\s*[\r\n]+\s*"""), " ")
    return if (display) "$$$flat$$" else "$$flat$"
}

/** Output of [MathScanner.split]: plain text interleaved with math. */
sealed interface InlineSegment {
    data class Text(val text: String) : InlineSegment
    data class Math(val span: MathSpan) : InlineSegment
}

/**
 * Finds LaTeX delimiters the way models emit them: `$…$` and `\(…\)` inline, `$$…$$` and
 * `\[…\]` display. Inline `$` follows the Pandoc rule so prices stay text: the opening `$`
 * must be followed by a non-space, the closing `$` preceded by a non-space and not followed
 * by a digit. `\$` is a literal dollar sign. Code spans are never scanned for math.
 */
object MathScanner {

    data class Match(val span: MathSpan, val end: Int)

    /** The math span starting exactly at [i], or null. */
    fun matchAt(text: String, i: Int): Match? {
        if (i >= text.length) return null
        val c = text[i]
        if (c == '$') {
            if (i > 0 && text[i - 1] == '\\') return null
            if (text.startsWith("$$", i)) {
                val close = text.indexOf("$$", i + 2)
                if (close < 0) return null
                val tex = text.substring(i + 2, close).trim()
                return if (tex.isEmpty()) null else Match(MathSpan(tex, true), close + 2)
            }
            val first = text.getOrNull(i + 1) ?: return null
            if (first.isWhitespace() || first == '$') return null
            var j = i + 1
            while (j < text.length) {
                if (text[j] == '$' && text[j - 1] != '\\') {
                    val before = text[j - 1]
                    val after = text.getOrNull(j + 1)
                    if (!before.isWhitespace() && (after == null || !after.isDigit())) {
                        return Match(MathSpan(text.substring(i + 1, j), false), j + 1)
                    }
                    // A `$` that cannot close also cannot be skipped over: it starts a price.
                    return null
                }
                j++
            }
            return null
        }
        if (c == '\\' && i + 1 < text.length) {
            val (closer, display) = when (text[i + 1]) {
                '(' -> "\\)" to false
                '[' -> "\\]" to true
                else -> return null
            }
            val close = text.indexOf(closer, i + 2)
            if (close < 0) return null
            val tex = text.substring(i + 2, close).trim()
            return if (tex.isEmpty()) null else Match(MathSpan(tex, display), close + 2)
        }
        return null
    }

    fun split(text: String): List<InlineSegment> {
        val out = mutableListOf<InlineSegment>()
        val buffer = StringBuilder()
        fun flush() {
            if (buffer.isNotEmpty()) {
                out += InlineSegment.Text(buffer.toString())
                buffer.setLength(0)
            }
        }
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (c == '`') {
                val end = text.indexOf('`', i + 1)
                if (end > i) {
                    buffer.append(text, i, end + 1)
                    i = end + 1
                    continue
                }
            }
            if (c == '\\' && text.getOrNull(i + 1) == '$') {
                buffer.append('$'); i += 2; continue
            }
            val m = matchAt(text, i)
            if (m != null) {
                flush()
                out += InlineSegment.Math(m.span)
                i = m.end
                continue
            }
            buffer.append(c)
            i++
        }
        flush()
        return out
    }
}

/**
 * Rewrites common LaTeX the typesetter does not know into equivalents it does. Purely
 * cosmetic substitutions: sizes (`\Big`), aliases (`\dfrac`, `\implies`), and wrapper
 * environments (`equation`, `align`). Anything still unsupported falls back to raw text.
 */
object TexNormalizer {

    private val rules: List<Pair<Regex, String>> = listOf(
        Regex("""\\(?:tag|label)\*?\{[^}]*\}""") to "",
        Regex("""\\(?:nonumber|notag|hline|nolimits|middle)(?![a-zA-Z])""") to "",
        Regex("""\\(?:big|Big|bigg|Bigg)[lrm]?(?![a-zA-Z])""") to "",
        Regex("""\\iiint(?![a-zA-Z])""") to """\\int\\!\\!\\!\\int\\!\\!\\!\\int""",
        Regex("""\\iint(?![a-zA-Z])""") to """\\int\\!\\!\\!\\int""",
        Regex("""\\oiint(?![a-zA-Z])""") to """\\oint\\!\\!\\!\\oint""",
        Regex("""\\[dtc]frac(?![a-zA-Z])""") to """\\frac""",
        Regex("""\\operatorname\*?\{([^}]*)\}""") to """\\mathrm{$1}""",
        Regex("""\\(?:boxed|overbrace|underbrace)\{""") to "{",
        Regex("""\\x(right|left)arrow(?:\[[^\]]*\])?\{[^}]*\}""") to """\\$1arrow""",
        Regex("""\\dots[cbmio]?(?![a-zA-Z])""") to """\\ldots""",
        Regex("""\\implies(?![a-zA-Z])""") to """\\Longrightarrow""",
        Regex("""\\impliedby(?![a-zA-Z])""") to """\\Longleftarrow""",
        Regex("""\\leqslant(?![a-zA-Z])""") to """\\leq""",
        Regex("""\\geqslant(?![a-zA-Z])""") to """\\geq""",
        Regex("""\\varnothing(?![a-zA-Z])""") to """\\emptyset""",
        Regex("""\\[lr]vert(?![a-zA-Z])""") to "|",
        Regex("""\\[lr]Vert(?![a-zA-Z])""") to """\\Vert""",
        Regex("""\\:""") to """\\>""",
        Regex("""(?<!\\)\\ """) to """\\;""",
        Regex("""\\%""") to "%",
        Regex("""\\begin\{array\}\{[^}]*\}""") to """\\begin{matrix}""",
        Regex("""\\end\{array\}""") to """\\end{matrix}""",
        Regex("""\\(begin|end)\{(?:align|alignat|flalign)\*?\}(?:\{\d+\})?""") to """\\$1{aligned}""",
        Regex("""\\(begin|end)\{(?:gather\*|multline\*?)\}""") to """\\$1{gather}""",
        Regex("""\\(begin|end)\{(?:smallmatrix)\}""") to """\\$1{matrix}""",
        Regex("""\\(begin|end)\{dcases\}""") to """\\$1{cases}""",
        Regex("""\\(?:begin|end)\{equation\*?\}""") to "",
        // Stacked notation the typesetter lacks, shown as limits/scripts on the base.
        Regex("""\\(?:overset|stackrel)\{([^{}]*)\}\{([^{}]*)\}""") to """{$2}^{$1}""",
        Regex("""\\underset\{([^{}]*)\}\{([^{}]*)\}""") to """{$2}_{$1}""",
        // Modular arithmetic.
        Regex("""\\pmod\{([^{}]*)\}""") to """\\;(\\mathrm{mod}\\;$1)""",
        Regex("""\\pmod\s*(\\[a-zA-Z]+|[^\s{}\\])""") to """\\;(\\mathrm{mod}\\;$1)""",
        Regex("""\\(?:bmod|mod)(?![a-zA-Z])""") to """\\;\\mathrm{mod}\\;""",
        // Negations written with \not.
        Regex("""\\not\s*=""") to """\\neq""",
        Regex("""\\not\s*\\in(?![a-zA-Z])""") to """\\notin""",
        // Spacing and invisible boxes.
        Regex("""\\hspace\*?\{[^{}]*\}""") to """\\;""",
        Regex("""\\vspace\*?\{[^{}]*\}""") to "",
        Regex("""\\[hv]?phantom\{[^{}]*\}""") to ""
    )

    fun normalize(tex: String): String {
        var s = symbols(tex.trim())
        for ((regex, replacement) in rules) s = regex.replace(s, replacement)
        s = primes(s)
        s = columns(s)
        return s.trim()
    }

    // The typesetter silently drops every character outside printable ASCII, and the
    // apostrophe too: f'(x) came out as f(x) and 30° as 30. These become commands it draws.

    private val superscriptDigits = mapOf(
        '⁰' to "0", '¹' to "1", '²' to "2", '³' to "3", '⁴' to "4", '⁵' to "5", '⁶' to "6", '⁷' to "7",
        '⁸' to "8", '⁹' to "9", '⁺' to "+", '⁻' to "-", '⁼' to "=", '⁽' to "(", '⁾' to ")", 'ⁿ' to "n", 'ⁱ' to "i"
    )
    private val subscriptDigits = mapOf(
        '₀' to "0", '₁' to "1", '₂' to "2", '₃' to "3", '₄' to "4", '₅' to "5", '₆' to "6", '₇' to "7",
        '₈' to "8", '₉' to "9", '₊' to "+", '₋' to "-", '₌' to "=", '₍' to "(", '₎' to ")",
        'ₐ' to "a", 'ₑ' to "e", 'ₓ' to "x", 'ᵢ' to "i", 'ⱼ' to "j", 'ₙ' to "n"
    )
    private val unicodeSymbols: Map<Char, String> = mapOf(
        '≤' to "\\le", '≥' to "\\ge", '≠' to "\\neq", '≈' to "\\approx", '≡' to "\\equiv",
        '∼' to "\\sim", '≅' to "\\cong", '∝' to "\\propto", '±' to "\\pm", '∓' to "\\mp",
        '×' to "\\times", '÷' to "\\div", '·' to "\\cdot", '⋅' to "\\cdot", '∘' to "\\circ",
        '−' to "-", '–' to "-", '—' to "-", '∞' to "\\infty", '∈' to "\\in", '∉' to "\\notin",
        '∋' to "\\ni", '⊂' to "\\subset", '⊆' to "\\subseteq", '⊃' to "\\supset", '⊇' to "\\supseteq",
        '∪' to "\\cup", '∩' to "\\cap", '∅' to "\\emptyset", '→' to "\\to", '←' to "\\leftarrow",
        '↔' to "\\leftrightarrow", '⇒' to "\\Rightarrow", '⇐' to "\\Leftarrow", '⇔' to "\\Leftrightarrow",
        '↦' to "\\mapsto", '∀' to "\\forall", '∃' to "\\exists", '¬' to "\\neg", '∧' to "\\land",
        '∨' to "\\lor", '∂' to "\\partial", '∇' to "\\nabla", '∫' to "\\int", '∮' to "\\oint",
        '∑' to "\\sum", '∏' to "\\prod", '°' to "^{\\circ}", '…' to "\\ldots", '⋯' to "\\cdots",
        '⟨' to "\\langle", '⟩' to "\\rangle", '‖' to "\\Vert", '⊥' to "\\perp", '∥' to "\\parallel",
        '∠' to "\\angle", '△' to "\\triangle", '∴' to "\\therefore", '∵' to "\\because",
        'ℝ' to "\\mathbb{R}", 'ℕ' to "\\mathbb{N}", 'ℤ' to "\\mathbb{Z}", 'ℚ' to "\\mathbb{Q}", 'ℂ' to "\\mathbb{C}",
        '✓' to "\\checkmark", '✔' to "\\checkmark", '✗' to "\\times", '✘' to "\\times",
        '“' to "\"", '”' to "\"", '‘' to "'", '’' to "'",
        'α' to "\\alpha", 'β' to "\\beta", 'γ' to "\\gamma", 'δ' to "\\delta", 'ε' to "\\varepsilon",
        'ζ' to "\\zeta", 'η' to "\\eta", 'θ' to "\\theta", 'ι' to "\\iota", 'κ' to "\\kappa",
        'λ' to "\\lambda", 'μ' to "\\mu", 'ν' to "\\nu", 'ξ' to "\\xi", 'π' to "\\pi", 'ρ' to "\\rho",
        'σ' to "\\sigma", 'τ' to "\\tau", 'υ' to "\\upsilon", 'φ' to "\\varphi", 'χ' to "\\chi",
        'ψ' to "\\psi", 'ω' to "\\omega", 'Γ' to "\\Gamma", 'Δ' to "\\Delta", 'Θ' to "\\Theta",
        'Λ' to "\\Lambda", 'Ξ' to "\\Xi", 'Π' to "\\Pi", 'Σ' to "\\Sigma", 'Υ' to "\\Upsilon",
        'Φ' to "\\Phi", 'Ψ' to "\\Psi", 'Ω' to "\\Omega"
    )
    private val squareRoot = Regex("""√\s*(\d+(?:\.\d+)?|[A-Za-z](?![A-Za-z])|\(([^()]*)\)|\{([^{}]*)\})""")

    private fun symbols(tex: String): String {
        if (tex.all { it.code < 0x7F }) return tex
        var s = squareRoot.replace(tex) { m ->
            val inside = m.groups[2]?.value ?: m.groups[3]?.value ?: m.groupValues[1]
            "\\sqrt{$inside}"
        }
        s = Regex("[${superscriptDigits.keys.joinToString("")}]+").replace(s) { m ->
            "^{" + m.value.map { superscriptDigits[it] }.joinToString("") + "}"
        }
        s = Regex("[${subscriptDigits.keys.joinToString("")}]+").replace(s) { m ->
            "_{" + m.value.map { subscriptDigits[it] }.joinToString("") + "}"
        }
        val out = StringBuilder(s.length + 16)
        for ((i, ch) in s.withIndex()) {
            val command = if (ch == '√') "\\surd" else unicodeSymbols[ch]
            if (command == null) {
                out.append(ch)
                continue
            }
            out.append(command)
            // A command needs a space before a letter: \theta x, not \thetax.
            if (command.last().isLetter() && s.getOrNull(i + 1)?.isLetter() == true) out.append(' ')
        }
        return out.toString()
    }

    /** Text the apostrophe stays an apostrophe in. */
    private val textGroup = Regex("""\\(?:text|textrm|textit|textbf|textsf|texttt|mbox)\{[^{}]*\}""")
    /** A run of primes, and a superscript right after it that joins them: y'^2. */
    private val primeRun = Regex("""(?<!\\)('+|[′″‴]+)(?:\^(\{([^{}]*)\}|\\[A-Za-z]+|[^\s{}\\]))?""")

    /**
     * `f'` and `y''` (or ′ ″ ‴) as `f\prime` and `y\prime\prime`. The font's prime glyph is
     * built to sit beside the letter at full size; as a superscript it came out small and
     * floating, with a gap before what follows.
     */
    private fun primes(tex: String): String {
        if ('\'' !in tex && tex.none { it in "′″‴" }) return tex
        val out = StringBuilder(tex.length + 16)
        var last = 0
        for (text in textGroup.findAll(tex)) {
            out.append(primesIn(tex.substring(last, text.range.first))).append(text.value)
            last = text.range.last + 1
        }
        return out.append(primesIn(tex.substring(last))).toString()
    }

    private fun primesIn(s: String): String = primeRun.replace(s) { m ->
        val count = m.groupValues[1].sumOf { c ->
            when (c) {
                '″' -> 2
                '‴' -> 3
                else -> 1
            }.toInt()
        }
        val primes = "\\prime".repeat(count)
        val power = m.groups[3]?.value ?: m.groups[2]?.value
        when {
            power != null -> "$primes^{$power}"
            // A command needs a space before a letter: \prime x, not \primex.
            s.getOrNull(m.range.last + 1)?.isLetter() == true -> "$primes "
            else -> primes
        }
    }

    private val cases = Regex("""\\begin\{cases\}(.*?)\\end\{cases\}""", RegexOption.DOT_MATCHES_ALL)
    private val aligned = Regex("""\\begin\{aligned\}(.*?)\\end\{aligned\}""", RegexOption.DOT_MATCHES_ALL)
    private val ampersand = Regex("""(?<!\\)&""")

    /**
     * The typesetter takes exactly two columns in cases and at most two in aligned. Cases
     * rows written without `&` get an empty second column; aligned rows keep their first `&`
     * and the rest (`&&` columns) become space.
     */
    private fun columns(tex: String): String {
        var s = cases.replace(tex) { m ->
            val body = m.groupValues[1]
            if ("\\begin" in body || ampersand.containsMatchIn(body)) return@replace m.value
            val rows = body.split("\\\\").joinToString("\\\\") { row -> if (row.isBlank()) row else "$row&" }
            "\\begin{cases}$rows\\end{cases}"
        }
        s = aligned.replace(s) { m ->
            val body = m.groupValues[1]
            if ("\\begin" in body) return@replace m.value
            val rows = body.split("\\\\").joinToString("\\\\") { row ->
                var first = true
                ampersand.replace(row) { if (first) { first = false; "&" } else "\\qquad " }
            }
            "\\begin{aligned}$rows\\end{aligned}"
        }
        return s
    }

    /**
     * Last resort for a formula that still fails on one unknown command: show the command's
     * name as upright text (its arguments still render), so the rest of the formula renders
     * instead of raw source, and nothing silently disappears from the maths.
     * Returns null when [command] does not occur.
     */
    fun replaceUnknownCommand(tex: String, command: String): String? {
        if (command.isBlank() || !command.all { it.isLetter() }) return null
        val regex = Regex("""\\$command(?![a-zA-Z])""")
        return if (regex.containsMatchIn(tex)) regex.replace(tex, """\\,\\mathrm{$command}\\,""") else null
    }

    /** Line breaks outside any environment only typeset inside a multi-line one. */
    fun wrapLines(tex: String): String? =
        if (tex.contains("\\\\") && !tex.contains("\\begin")) {
            "\\begin{gather}$tex\\end{gather}"
        } else null
}

/**
 * Inline Markdown: `code`, **bold**, *italic*, ~~strike~~, [links](https://…) and math.
 *
 * [mathContent] is asked for an inline-content id for each math span; returning null means
 * the formula could not be typeset, and its source is shown as code instead.
 */
object InlineMarkdown {

    const val MATH_PREFIX = "math:"

    fun build(
        text: String,
        linkColor: Color,
        mathContent: (MathSpan) -> String?
    ): AnnotatedString = buildAnnotatedString {
        val buffer = StringBuilder()
        fun flush() {
            if (buffer.isNotEmpty()) {
                append(buffer.toString())
                buffer.setLength(0)
            }
        }
        val code = SpanStyle(fontFamily = FontFamily.Monospace, background = Color(0x22808080))

        var i = 0
        while (i < text.length) {
            val ch = text[i]

            if (ch == '\\' && text.getOrNull(i + 1) == '$') {
                buffer.append('$'); i += 2; continue
            }

            val math = MathScanner.matchAt(text, i)
            if (math != null) {
                flush()
                val id = mathContent(math.span)
                if (id != null) {
                    appendInlineContent(id, mathSource(math.span.tex, math.span.display))
                } else {
                    withStyle(code) { append(math.span.tex) }
                }
                i = math.end
                continue
            }

            when {
                ch == '`' -> {
                    val end = text.indexOf('`', i + 1)
                    if (end > i) {
                        flush()
                        withStyle(code) { append(text.substring(i + 1, end)) }
                        i = end + 1
                    } else {
                        buffer.append(ch); i++
                    }
                }

                ch == '[' -> {
                    val close = text.indexOf("](", i + 1)
                    val end = if (close > i) text.indexOf(')', close + 2) else -1
                    val url = if (end > close) text.substring(close + 2, end).trim() else ""
                    if (end > close && (url.startsWith("https://") || url.startsWith("http://"))) {
                        flush()
                        withLink(
                            LinkAnnotation.Url(
                                url,
                                TextLinkStyles(
                                    SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline)
                                )
                            )
                        ) { append(text.substring(i + 1, close)) }
                        i = end + 1
                    } else {
                        buffer.append(ch); i++
                    }
                }

                ch == '*' && text.getOrNull(i + 1) == '*' -> {
                    val end = text.indexOf("**", i + 2)
                    if (end > i + 2) {
                        flush()
                        withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                            append(build(text.substring(i + 2, end), linkColor, mathContent))
                        }
                        i = end + 2
                    } else {
                        buffer.append(ch); i++
                    }
                }

                ch == '*' && text.getOrNull(i + 1)?.isWhitespace() == false -> {
                    val end = findItalicEnd(text, i + 1)
                    if (end > 0) {
                        flush()
                        withStyle(SpanStyle(fontStyle = FontStyle.Italic)) {
                            append(build(text.substring(i + 1, end), linkColor, mathContent))
                        }
                        i = end + 1
                    } else {
                        buffer.append(ch); i++
                    }
                }

                ch == '~' && text.getOrNull(i + 1) == '~' -> {
                    val end = text.indexOf("~~", i + 2)
                    if (end > i) {
                        flush()
                        withStyle(SpanStyle(textDecoration = TextDecoration.LineThrough)) {
                            append(text.substring(i + 2, end))
                        }
                        i = end + 2
                    } else {
                        buffer.append(ch); i++
                    }
                }

                else -> {
                    buffer.append(ch); i++
                }
            }
        }
        flush()
    }

    /** A closing `*` must follow a non-space and must not be part of `**`. */
    private fun findItalicEnd(text: String, from: Int): Int {
        var j = from
        while (j < text.length) {
            if (text[j] == '*' && !text[j - 1].isWhitespace() && text.getOrNull(j + 1) != '*') return j
            j++
        }
        return -1
    }
}
