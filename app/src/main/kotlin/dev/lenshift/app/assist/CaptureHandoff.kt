package dev.lenshift.app.assist

import android.graphics.Bitmap

/**
 * Hands the assist bitmap from the session (a Service process) to the selection activity
 * without serialising it through an Intent.
 *
 * The handoff is single-shot and cleared on read, so a stale screenshot can never leak
 * into a later invocation. The reference is dropped as soon as the activity consumes it.
 */
object CaptureHandoff {

    @Volatile
    private var pending: Bitmap? = null

    @Volatile
    private var consumedAt: Long = 0L

    fun offer(bitmap: Bitmap) {
        pending = bitmap
        consumedAt = 0L
    }

    fun take(): Bitmap? {
        val value = pending
        pending = null
        if (value != null) consumedAt = System.currentTimeMillis()
        return value
    }

    fun peek(): Bitmap? = pending

    fun discard() {
        pending = null
    }

    internal fun lastConsumedAt(): Long = consumedAt
}
