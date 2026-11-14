package dev.lenshift.app.ai

/**
 * Code a model ran in its own sandbox that a relay leaked into the answer as plain text.
 *
 * gpt-5.x on ChatGPT accounts looks closer at photos with its Python tool, and some relays
 * pass that code through ahead of the real answer:
 *
 *     from PIL import Image
 *     img=Image.open('/mnt/data/image_1.jpg')
 *     display(img.crop((120,330,1430,600)).resize((1965,405)))
 *     Not quite — there are a few mismatches …
 *
 * A run of code lines outside code fences is removed when it is plainly that tool's code
 * (it opens or shows the sandboxed image, or imports PIL). Code an answer shows on purpose,
 * in a fence, stays.
 */
internal object LeakedToolCode {

    /** A line only the sandbox's own code would hold. */
    private val SIGNAL = Regex("""/mnt/data/|from PIL import|^\s*import PIL|Image\.open\(|display\(img""")

    /** One line of code: an import, a call, an assignment from a call, or a comment. */
    private val CODE = Regex(
        """^\s*(?:from\s+[\w.]+\s+import\s+\S.*|import\s+[\w.]+(?:\s*,\s*[\w.]+)*(?:\s+as\s+\w+)?""" +
            """|[A-Za-z_][\w.]*(?:\[[^\]]*\])?\s*=\s*[A-Za-z_][\w.]*\(.*\)|[A-Za-z_][\w.]*\(.*\)|#.*)\s*;?\s*$"""
    )

    fun strip(answer: String): String {
        if (!SIGNAL.containsMatchIn(answer)) return answer
        val lines = answer.lines()
        val kept = ArrayList<String>(lines.size)
        var inFence = false
        var removed = false
        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            if (line.trimStart().startsWith("```")) {
                inFence = !inFence
                kept += line
                i++
                continue
            }
            if (inFence || !CODE.matches(line)) {
                kept += line
                i++
                continue
            }
            var end = i
            while (end < lines.size && !lines[end].trimStart().startsWith("```") && CODE.matches(lines[end])) end++
            val run = lines.subList(i, end)
            if (run.any { SIGNAL.containsMatchIn(it) }) removed = true else kept += run
            i = end
        }
        if (!removed) return answer
        // A run removed at the very start leaves no gap before the answer.
        return kept.dropWhile { it.isBlank() }.joinToString("\n")
    }
}
