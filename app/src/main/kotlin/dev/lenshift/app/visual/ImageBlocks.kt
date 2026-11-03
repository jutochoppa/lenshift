package dev.lenshift.app.visual

import dev.lenshift.app.ui.markdown.MarkdownParser
import dev.lenshift.app.ui.markdown.MdBlock
import org.json.JSONObject

/** Which kind of visual a fenced block of an answer asks for, by its language tag. */
enum class VisualKind {
    GRAPH, GRAPH3D, IMAGE;

    companion object {
        fun of(language: String?): VisualKind? = when (language?.trim()?.lowercase()) {
            "graph", "graph2d", "plot", "plot2d" -> GRAPH
            "graph3d", "plot3d", "3d" -> GRAPH3D
            "image", "picture", "img" -> IMAGE
            else -> null
        }
    }
}

/**
 * The ```image blocks of an answer, numbered in order. The renderer and the conversation
 * both find them through [MarkdownParser], so block n on screen is request n.
 */
object ImageBlocks {

    /** What one block asks for. [input] is "selection" or "previous" when it edits an image. */
    data class Request(val index: Int, val prompt: String, val aspect: String?, val input: String?, val complete: Boolean)

    fun find(markdown: String): List<Request> =
        MarkdownParser.parse(markdown)
            .filterIsInstance<MdBlock.Code>()
            .filter { VisualKind.of(it.language) == VisualKind.IMAGE }
            .mapIndexed { i, block -> parse(block.code, i, block.complete) }

    /** A block's body: the prompt as text, or JSON with prompt, aspect and input. */
    fun parse(body: String, index: Int = 0, complete: Boolean = true): Request {
        val trimmed = body.trim()
        if (trimmed.startsWith("{")) {
            runCatching { JSONObject(VisualSpec.cleanJson(trimmed)) }.getOrNull()?.let { json ->
                val prompt = json.optString("prompt").trim()
                if (prompt.isNotEmpty()) {
                    return Request(
                        index,
                        prompt,
                        aspect(json.optString("aspect").ifBlank { json.optString("aspectRatio") }),
                        json.optString("input").trim().lowercase().takeIf { it == "selection" || it == "previous" },
                        complete
                    )
                }
            }
        }
        return Request(index, trimmed, null, null, complete)
    }

    /** "16:9", "landscape", "square", … as a ratio the image APIs know, or null. */
    fun aspect(text: String?): String? = when (text?.trim()?.lowercase()) {
        null, "" -> null
        "landscape", "wide", "horizontal" -> "16:9"
        "portrait", "tall", "vertical" -> "9:16"
        "square" -> "1:1"
        "1:1", "16:9", "9:16", "4:3", "3:4", "3:2", "2:3", "21:9", "5:4", "4:5" -> text.trim()
        else -> null
    }
}
