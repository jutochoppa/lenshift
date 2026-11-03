package dev.lenshift.app.assist

import android.content.Context
import android.content.SharedPreferences
import android.service.voice.VoiceInteractionSession
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject

/**
 * Records *which* physical trigger reached Lenshift, so the diagnostics screen can report
 * honestly whether the assistant gesture, a launcher shortcut, the tile, or a fallback
 * capture is what actually works on this device.
 *
 * This is local-only diagnostic state (a small ring buffer of counters). Nothing is ever
 * transmitted, and it stores no screen content.
 */
object AssistTriggerLog {

    private const val TAG = "Lenshift/Trigger"
    private const val PREFS = "lenshift_trigger_diag"
    private const val KEY_EVENTS = "events"
    private const val MAX_EVENTS = 40

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun recordShow(context: Context, showFlags: Int, args: android.os.Bundle?) {
        val sources = buildList {
            if (showFlags and VoiceInteractionSession.SHOW_WITH_ASSIST != 0) add("assist")
            if (showFlags and VoiceInteractionSession.SHOW_WITH_SCREENSHOT != 0) add("screenshot")
            if (showFlags and VoiceInteractionSession.SHOW_SOURCE_ASSIST_GESTURE != 0) add("gesture")
            if (showFlags and VoiceInteractionSession.SHOW_SOURCE_ACTIVITY != 0) add("activity")
            if (showFlags and VoiceInteractionSession.SHOW_SOURCE_APPLICATION != 0) add("application")
            if (showFlags and VoiceInteractionSession.SHOW_SOURCE_NOTIFICATION != 0) add("notification")
            if (showFlags and VoiceInteractionSession.SHOW_SOURCE_PUSH_TO_TALK != 0) add("ptt")
        }
        append(context, kind = "show", detail = sources.joinToString(",").ifEmpty { "none" })
    }

    fun recordSessionShow(context: Context, showFlags: Int) {
        append(context, kind = "session_show", detail = "flags=$showFlags")
    }

    fun recordScreenshot(context: Context, delivered: Boolean) {
        append(context, kind = "screenshot", detail = if (delivered) "delivered" else "null")
    }

    fun recordActivation(context: Context, mechanism: String) {
        append(context, kind = "activation", detail = mechanism)
    }

    fun recordCapture(context: Context, source: String, ok: Boolean) {
        append(context, kind = "capture", detail = "$source:${if (ok) "ok" else "failed"}")
    }

    fun recent(context: Context): List<TriggerEvent> {
        val raw = prefs(context).getString(KEY_EVENTS, null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (i in 0 until array.length()) {
                    val obj = array.getJSONObject(i)
                    add(
                        TriggerEvent(
                            timestamp = obj.optLong("t"),
                            kind = obj.optString("k"),
                            detail = obj.optString("d")
                        )
                    )
                }
            }
        }.getOrDefault(emptyList())
    }

    fun clear(context: Context) {
        prefs(context).edit().remove(KEY_EVENTS).apply()
    }

    private fun append(context: Context, kind: String, detail: String) {
        Log.i(TAG, "$kind: $detail")
        runCatching {
            val p = prefs(context)
            val existing = p.getString(KEY_EVENTS, null)
            val array = existing?.let { runCatching { JSONArray(it) }.getOrNull() } ?: JSONArray()
            val obj = JSONObject()
                .put("t", System.currentTimeMillis())
                .put("k", kind)
                .put("d", detail)
            array.put(obj)
            while (array.length() > MAX_EVENTS) array.remove(0)
            p.edit().putString(KEY_EVENTS, array.toString()).apply()
        }
    }

    data class TriggerEvent(val timestamp: Long, val kind: String, val detail: String)
}
