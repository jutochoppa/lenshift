package dev.lenshift.app.data.settings

import org.json.JSONObject
import java.util.UUID

/**
 * A one-tap action offered on the selection screen: the name on its chip and the prompt it
 * sends. Lenshift starts with [STOCK]; the user can edit, reorder or delete those and add
 * their own (see [AppSettings.quickActions]).
 *
 * The prompt is injected as a *user* message — never as a system instruction — because the
 * screen content it refers to is untrusted.
 */
data class QuickAction(
    val id: String,
    val label: String,
    val prompt: String
) {
    /** One of the actions Lenshift ships with, possibly edited. */
    val isStock: Boolean get() = STOCK.any { it.id == id }

    /** The stock action as shipped, when this one has been changed from it. */
    val original: QuickAction? get() = STOCK.firstOrNull { it.id == id }?.takeIf { it != this }

    /**
     * True for Explain with its stock prompt: a few selected words on their own give it no
     * context, so the selection screen asks about them directly instead. An edited prompt
     * is always sent as written.
     */
    val asksAboutShortText: Boolean get() = id == EXPLAIN.id && prompt == EXPLAIN.prompt

    /** This action with a new name and prompt; a blank name is taken from the prompt. */
    fun edited(label: String, prompt: String): QuickAction =
        copy(label = labelFor(label, prompt), prompt = prompt.trim().take(MAX_PROMPT))

    fun toJson(): JSONObject = JSONObject().put("id", id).put("label", label).put("prompt", prompt)

    companion object {
        const val MAX_LABEL = 40
        const val MAX_PROMPT = 4000

        /**
         * No action: the user's own message goes as typed. Sent with nothing typed, the model
         * gets only the selection (and the system prompt) and decides what to do with it.
         */
        val ASK = QuickAction("ask", "None (model decides)", "")

        val EXPLAIN = QuickAction(
            "explain", "Explain",
            "Explain what this shows as if I had just asked about it. Be concise."
        )
        val TRANSLATE = QuickAction(
            "translate", "Translate",
            "Translate the selected text into English. If it is already English, " +
                "translate it into Spanish. Output only the translation."
        )
        val SOLVE = QuickAction(
            "solve", "Solve",
            "Solve the problem shown. Show the reasoning steps briefly, then the answer."
        )
        val SUMMARIZE = QuickAction(
            "summarize", "Summarize",
            "Summarize the selected content in at most three sentences."
        )
        val DEFINE = QuickAction(
            "define", "Define",
            "Define the key term or phrase in the selection, then give one example of its use."
        )
        val IDENTIFY = QuickAction(
            "identify", "What is this?",
            "Identify what is shown in the image. If it is a product, landmark, " +
                "plant, animal, or artwork, name it and say how confident you are."
        )
        val COPY_TEXT = QuickAction(
            "copy_text", "Extract text",
            "Return only the text visible in the selection, preserving line breaks."
        )

        /** The actions a new install offers, in order. */
        val STOCK: List<QuickAction> = listOf(EXPLAIN, TRANSLATE, SOLVE, SUMMARIZE, DEFINE, IDENTIFY, COPY_TEXT)

        /** A shipped action (or [ASK]) by id, whatever the user has done to their own list. */
        fun stock(id: String?): QuickAction? = if (id == ASK.id) ASK else STOCK.firstOrNull { it.id == id }

        /** A new action of the user's. */
        fun custom(label: String, prompt: String, id: String = "custom-${UUID.randomUUID()}"): QuickAction =
            QuickAction(id, labelFor(label, prompt), prompt.trim().take(MAX_PROMPT))

        /** [label] tidied to fit a chip; blank takes the prompt's first sentence. */
        fun labelFor(label: String, prompt: String): String {
            val name = label.trim().ifEmpty { firstSentence(prompt) }.replace(Regex("""\s+"""), " ")
            return if (name.length <= MAX_LABEL) name else name.take(MAX_LABEL - 1).trimEnd() + "…"
        }

        /** "Is this correct? Check each step." → "Is this correct?" */
        private fun firstSentence(prompt: String): String {
            val line = prompt.trim().lineSequence().firstOrNull().orEmpty().trim()
            val end = Regex("""[.?!](?=\s)""").find(line)?.range?.first ?: return line
            return line.substring(0, end + 1)
        }

        /** A saved action, or null when it has no id or prompt. */
        fun fromJson(json: JSONObject): QuickAction? {
            val id = json.optString("id").trim()
            val prompt = json.optString("prompt").trim()
            if (id.isEmpty() || id == ASK.id || prompt.isEmpty()) return null
            return QuickAction(id, labelFor(json.optString("label"), prompt), prompt.take(MAX_PROMPT))
        }
    }
}
