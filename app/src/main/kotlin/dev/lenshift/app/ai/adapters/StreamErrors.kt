package dev.lenshift.app.ai.adapters

import org.json.JSONObject

/**
 * Error events a relay invents for itself, such as ChatGPT2API's `UpstreamHTTPError`. A
 * stream that sends one and then ends would otherwise look like an empty answer; with
 * this it fails with the relay's own message.
 */
internal object StreamErrors {

    private val ERROR_KIND = Regex("""(?i)error|exception|fail""")

    /** Whether an event's name or `type` says it reports a failure. */
    fun isError(kind: String?): Boolean = kind != null && ERROR_KIND.containsMatchIn(kind)

    /** The failure's message: `error.message`, `error`, `message`, `detail` …, else the text itself. */
    fun message(data: String): String? {
        val text = data.trim()
        if (text.isEmpty()) return null
        val json = runCatching { JSONObject(text) }.getOrNull() ?: return text.take(MAX)
        val error = json.opt("error")
        val found = listOf(
            json.optJSONObject("response")?.optJSONObject("error")?.optString("message"),
            (error as? JSONObject)?.optString("message"),
            error as? String,
            json.optString("message"),
            json.optString("detail"),
            json.optString("msg"),
            json.optString("title")
        ).firstOrNull { !it.isNullOrBlank() && it != "null" }?.trim()
        return (found ?: text).take(MAX)
    }

    private const val MAX = 300
}
