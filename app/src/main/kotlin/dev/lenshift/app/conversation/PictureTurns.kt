package dev.lenshift.app.conversation

import org.json.JSONObject

/**
 * The answer of a turn sent to an image model picked in the chat: a ```image block of what
 * the user asked for, so it is made, shown, retried and saved like a picture any answer
 * asks for, and a chat model asked later sees what was made.
 */
object PictureTurns {

    /**
     * Which picture to edit: the user's attachments on this turn, else the previous
     * picture of the conversation, else what they selected on screen; null makes a new one.
     */
    fun input(attached: Boolean, previous: Boolean, selection: Boolean): String? = when {
        attached -> "attached"
        previous -> "previous"
        selection -> "selection"
        else -> null
    }

    /** The answer for [prompt], or null when nothing was asked. */
    fun answer(prompt: String, input: String?): String? {
        val text = prompt.trim()
        if (text.isEmpty()) return null
        val block = JSONObject().put("prompt", text)
        if (input != null) block.put("input", input)
        return "```image\n$block\n```"
    }
}
