package dev.lenshift.app.assist

import android.app.assist.AssistStructure

/**
 * Pulls plain text out of an [AssistStructure].
 *
 * Lenshift uses this only as a *hint* for the selection screen — for example to show
 * which app the assist came from and to seed the OCR preview when no bitmap arrives.
 *
 * Security note: text recovered from an assist structure is attacker-controlled input.
 * It is never concatenated into system instructions and never treated as instructions
 * to the model; see `PromptBuilder`, which fences all screen-derived text as data.
 */
object AssistTextExtractor {

    private const val MAX_CHARS = 20_000
    private const val MAX_DEPTH = 40

    fun extract(structure: AssistStructure?): String? {
        if (structure == null) return null
        val sb = StringBuilder()
        val collector = Collector(sb)
        for (i in 0 until structure.windowNodeCount) {
            val root = structure.getWindowNodeAt(i).rootViewNode ?: continue
            collector.visit(root, 0)
            if (sb.length >= MAX_CHARS) break
        }
        return sb.toString().trim().ifEmpty { null }
    }

    private class Collector(private val sb: StringBuilder) {

        fun visit(node: AssistStructure.ViewNode, depth: Int) {
            if (depth > MAX_DEPTH || sb.length >= MAX_CHARS) return

            // Prefer the accessibility-visible text, then the content description,
            // then the hint.
            val text = sequenceOf(
                node.text,
                node.contentDescription,
                node.hint
            ).firstOrNull { !it.isNullOrBlank() }

            if (!text.isNullOrBlank()) {
                append(text.toString())
            }

            if (node.childCount > 0) {
                for (i in 0 until node.childCount) {
                    visit(node.getChildAt(i), depth + 1)
                    if (sb.length >= MAX_CHARS) return
                }
            }
        }

        private fun append(value: String) {
            val trimmed = value.trim()
            if (trimmed.isEmpty()) return
            if (sb.length + trimmed.length + 1 > MAX_CHARS) return
            if (sb.isNotEmpty()) sb.append('\n')
            sb.append(trimmed)
        }
    }
}
