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
        var s = tex.trim()
        for ((regex, replacement) in rules) s = regex.replace(s, replacement)
        return s.trim()
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
                    appendInlineContent(id, if (math.span.display) "$$${math.span.tex}$$" else "$${math.span.tex}$")
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
