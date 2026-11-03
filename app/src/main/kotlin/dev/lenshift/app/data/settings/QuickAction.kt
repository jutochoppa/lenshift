package dev.lenshift.app.data.settings

/**
 * The one-tap actions offered on the selection screen.
 *
 * Each carries its own prompt fragment, which is injected as a *user* message — never
 * as a system instruction — because the screen content it refers to is untrusted.
 */
enum class QuickAction(
    val id: String,
    val label: String,
    val icon: String,
    val prompt: String,
    val wantsImage: Boolean
) {
    /**
     * No action: the user's own message goes as typed. Sent with nothing typed, the model
     * gets only the selection (and the system prompt) and decides what to do with it.
     */
    ASK(
        id = "ask",
        label = "None (model decides)",
        icon = "chat",
        prompt = "",
        wantsImage = true
    ),
    EXPLAIN(
        id = "explain",
        label = "Explain",
        icon = "info",
        prompt = "Explain what this shows as if I had just asked about it. Be concise.",
        wantsImage = true
    ),
    TRANSLATE(
        id = "translate",
        label = "Translate",
        icon = "translate",
        prompt = "Translate the selected text into English. If it is already English, " +
            "translate it into Spanish. Output only the translation.",
        wantsImage = false
    ),
    SOLVE(
        id = "solve",
        label = "Solve",
        icon = "calculate",
        prompt = "Solve the problem shown. Show the reasoning steps briefly, then the answer.",
        wantsImage = true
    ),
    SUMMARIZE(
        id = "summarize",
        label = "Summarize",
        icon = "summarize",
        prompt = "Summarize the selected content in at most three sentences.",
        wantsImage = false
    ),
    DEFINE(
        id = "define",
        label = "Define",
        icon = "book",
        prompt = "Define the key term or phrase in the selection, then give one example " +
            "of its use.",
        wantsImage = false
    ),
    IDENTIFY(
        id = "identify",
        label = "What is this?",
        icon = "search",
        prompt = "Identify what is shown in the image. If it is a product, landmark, " +
            "plant, animal, or artwork, name it and say how confident you are.",
        wantsImage = true
    ),
    COPY_TEXT(
        id = "copy_text",
        label = "Extract text",
        icon = "copy",
        prompt = "Return only the text visible in the selection, preserving line breaks.",
        wantsImage = true
    );

    companion object {
        fun fromId(id: String?): QuickAction =
            entries.firstOrNull { it.id == id } ?: ASK

        /** Like [fromId], but null when [id] names no action. */
        fun fromIdOrNull(id: String?): QuickAction? = entries.firstOrNull { it.id == id }

        /** Actions offered inline; the rest live behind the "more" affordance. */
        val primary: List<QuickAction> = listOf(EXPLAIN, TRANSLATE, SOLVE, IDENTIFY)
    }
}
