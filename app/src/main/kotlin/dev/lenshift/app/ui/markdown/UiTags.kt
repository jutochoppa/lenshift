package dev.lenshift.app.ui.markdown

import org.json.JSONArray
import org.json.JSONObject

/**
 * Display tags some models write for the ChatGPT app, turned into Markdown Lenshift shows.
 *
 * gpt-5.x through a ChatGPT account answers with `<badge color="info">Step 1</badge>`,
 * `<text size="sm">…</text>`, `<caption>…</caption>`, `<box …>` wrappers and Desmos-style
 * `<Graph expressions={[…]} />`, which Lenshift has no components for. A badge becomes bold,
 * a caption italic, other paired tags keep their words, a line holding only an opening or
 * closing tag goes, a Graph becomes a ```graph block and other components are dropped.
 * Fenced code, `code spans` and `$…$` math are left as they are.
 */
internal object UiTags {

    fun clean(markdown: String): String {
        if ('<' !in markdown) return markdown
        val lines = markdown.lines()
        val out = ArrayList<String>(lines.size)
        var inFence = false
        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            val trimmed = line.trim()
            when {
                trimmed.startsWith("```") -> {
                    inFence = !inFence
                    out += line
                }
                inFence || '<' !in line -> out += line
                COMPONENT_START.containsMatchIn(trimmed) -> {
                    val name = COMPONENT_START.find(trimmed)!!.groupValues[1]
                    val source = StringBuilder(trimmed)
                    var end = i
                    while (!closes(source, name) && end + 1 < lines.size && end - i < MAX_COMPONENT_LINES) {
                        end++
                        source.append('\n').append(lines[end].trim())
                    }
                    when {
                        closes(source, name) -> {
                            if (name == "Graph") out += graphBlock(source.toString())
                            i = end
                        }
                        // Unclosed: a component still streaming (hidden) or ordinary text such as "<Enter>".
                        "={" in source -> i = end
                        else -> out += line
                    }
                }
                WRAPPER_LINE.matches(trimmed) -> when (WRAPPER_LINE.find(trimmed)!!.groupValues[1]) {
                    "hr" -> out += "---"
                    "br" -> out += ""
                }
                else -> out += inline(line)
            }
            i++
        }
        return out.joinToString("\n")
    }

    /** Paired tags within one line, outside code spans and math. */
    private fun inline(line: String): String {
        val kept = mutableListOf<String>()
        var text = PROTECTED.replace(line) { kept += it.value; "\u0001${kept.size - 1}\u0001" }
        repeat(MAX_NESTING) {
            val next = PAIRED.replace(text) { m ->
                val (name, attributes, content) = m.destructured
                val words = content.trim()
                when {
                    words.isEmpty() -> ""
                    name in BOLD || (name == "text" && BOLD_WEIGHT.containsMatchIn(attributes)) -> emphasise(words, "**")
                    name in ITALIC -> emphasise(words, "*")
                    name == "code" || name == "kbd" -> "`$words`"
                    else -> words
                }
            }
            if (next == text) return@repeat
            text = next
        }
        text = LINE_BREAK.replace(text, " ")
        return Regex("\u0001(\\d+)\u0001").replace(text) { kept[it.groupValues[1].toInt()] }
    }

    private fun emphasise(words: String, mark: String) =
        if (words.startsWith(mark) && words.endsWith(mark)) words else "$mark$words$mark"

    private fun closes(source: CharSequence, name: String): Boolean {
        val text = source.trimEnd()
        return (text.endsWith("/>") && balanced(text)) || text.contains("</$name>")
    }

    /** Whether every `{` and `[` outside strings is closed, so a `/>` inside an attribute is not the end. */
    private fun balanced(text: CharSequence): Boolean {
        var depth = 0
        var quote: Char? = null
        var escaped = false
        for (ch in text) {
            when {
                escaped -> escaped = false
                quote != null -> if (ch == '\\') escaped = true else if (ch == quote) quote = null
                ch == '"' || ch == '\'' -> quote = ch
                ch == '{' || ch == '[' -> depth++
                ch == '}' || ch == ']' -> depth--
            }
        }
        return depth == 0 && quote == null
    }

    /** `<Graph expressions={[{"latex": "y=x^2", "restrictions": ["0\le x\le 1"]}, …]} />` as a ```graph block. */
    private fun graphBlock(source: String): List<String> {
        val expressions = attribute(source, "expressions")
            ?.let { runCatching { JSONArray(it) }.getOrNull() } ?: return emptyList()
        val items = JSONArray()
        for (k in 0 until expressions.length()) {
            val expression = expressions.optJSONObject(k) ?: continue
            val latex = expression.optString("latex").trim()
            if (latex.isEmpty()) continue
            val item = item(latex, expression.optJSONArray("restrictions")) ?: continue
            expression.optString("color").takeIf { it.startsWith("#") }?.let { item.put("color", it) }
            items.put(item)
        }
        if (items.length() == 0) return emptyList()
        return listOf("```graph", JSONObject().put("items", items).toString(), "```")
    }

    private fun item(latex: String, restrictions: JSONArray?): JSONObject? {
        POINT.matchEntire(latex)?.let { m ->
            val x = m.groupValues[1].trim().toDoubleOrNull()
            val y = m.groupValues[2].trim().toDoubleOrNull()
            return if (x != null && y != null) JSONObject().put("point", JSONArray().put(x).put(y)) else null
        }
        val label = "\$" + latex + "\$"
        val explicit = EXPLICIT.matchEntire(latex)
        if (explicit != null && !RELATION.containsMatchIn(explicit.groupValues[2])) {
            val along = if (explicit.groupValues[1] == "y") "x" else "y"
            return JSONObject().put("fn", latex).put("label", label).also { fn ->
                domain(restrictions, along)?.let { fn.put("domain", it) }
            }
        }
        if (RELATION.containsMatchIn(latex)) return JSONObject().put("implicit", latex).put("label", label)
        return JSONObject().put("fn", latex).put("label", label).also { fn ->
            domain(restrictions, "x")?.let { fn.put("domain", it) }
        }
    }

    /** The `a ≤ v ≤ b` restriction on [variable] as [a, b]. */
    private fun domain(restrictions: JSONArray?, variable: String): JSONArray? {
        restrictions ?: return null
        for (k in 0 until restrictions.length()) {
            val m = RANGE.matchEntire(restrictions.optString(k).trim()) ?: continue
            if (m.groupValues[2] != variable) continue
            return JSONArray().put(bound(m.groupValues[1])).put(bound(m.groupValues[3]))
        }
        return null
    }

    private fun bound(text: String): Any = text.trim().toDoubleOrNull() ?: text.trim()

    /** The value of a JSX attribute: the text inside `name={…}`, or inside `name="…"`. */
    private fun attribute(source: String, name: String): String? {
        val start = Regex("""\b$name\s*=\s*""").find(source) ?: return null
        var at = start.range.last + 1
        if (at >= source.length) return null
        if (source[at] == '"') return source.substring(at + 1).substringBefore('"')
        if (source[at] != '{') return null
        val open = at
        var depth = 0
        var quote: Char? = null
        var escaped = false
        while (at < source.length) {
            val ch = source[at]
            when {
                escaped -> escaped = false
                quote != null -> if (ch == '\\') escaped = true else if (ch == quote) quote = null
                ch == '"' || ch == '\'' -> quote = ch
                ch == '{' -> depth++
                ch == '}' -> if (--depth == 0) return source.substring(open + 1, at).trim()
            }
            at++
        }
        return null
    }

    private const val MAX_COMPONENT_LINES = 60
    private const val MAX_NESTING = 4

    private const val ATTRIBUTES = """(?:\s+[A-Za-z_:][\w:.-]*(?:\s*=\s*(?:"[^"]*"|'[^']*'|\{[^{}]*\}))?)*"""

    /** A JSX component at the start of a line: `<Graph`, `<Chart …`. Needs two letters, so `<T>` and `<A, B>` stay. */
    private val COMPONENT_START = Regex("""^<([A-Z][A-Za-z0-9]+)(?=\s|/?>|$)""")
    private val WRAPPER_LINE = Regex("""^</?([a-z][a-z0-9-]*)$ATTRIBUTES\s*/?>$""")
    private val PAIRED = Regex("""<([a-z][a-z0-9-]*)($ATTRIBUTES)\s*>(.*?)</\1\s*>""")
    private val PROTECTED = Regex("""`[^`\n]*`|\$\$[^$]*\$\$|\$[^$\n]*\$""")
    /** `<br>` inside a line, common in table cells; a line holds no breaks here. */
    private val LINE_BREAK = Regex("""\s*<br\s*/?>\s*""")
    private val BOLD = setOf("badge", "b", "strong")
    private val ITALIC = setOf("caption", "i", "em")
    private val BOLD_WEIGHT = Regex("""weight\s*=\s*["']?(?:bold|semibold)""")

    private val POINT = Regex("""\(\s*([^,()]+),\s*([^,()]+)\)""")
    private val EXPLICIT = Regex("""\s*([xy])\s*=(.*)""")
    private val RELATION = Regex("""=|<|>|\\le|\\ge|\\lt|\\gt|≤|≥""")
    private val RANGE = Regex("""(.+?)\s*(?:<=|<|≤|\\leq?|\\lt)\s*([a-z])\s*(?:<=|<|≤|\\leq?|\\lt)\s*(.+)""")
}
