package dev.lenshift.app.capture

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import dev.lenshift.app.assist.CaptureHandoff
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Chooses *how* to get pixels, in strict priority order:
 *
 *   1. ASSIST_SCREENSHOT  - already handed to us by the platform (no consent prompt).
 *   2. ACCESSIBILITY      - optional, user-enabled, single screenshot per activation.
 *   3. MEDIA_PROJECTION   - last resort, always shows the system consent dialog.
 *
 * The coordinator never captures on its own initiative: it is called only from an
 * activity the user just triggered.
 */
class CaptureCoordinator(private val context: Context) {

    private val _lastAttempts = MutableStateFlow<List<CaptureAttempt>>(emptyList())
    val lastAttempts: StateFlow<List<CaptureAttempt>> = _lastAttempts.asStateFlow()

    /**
     * Returns the assist bitmap if the session stashed one, without falling back.
     * Used by the activity's first frame so the screen can freeze instantly.
     */
    fun takeAssistScreenshot(): Bitmap? = CaptureHandoff.take()

    /**
     * Runs the fallback chain. [allowMediaProjection] is false on the first pass so the
     * UI can show "enable the fallback" affordances instead of throwing a system dialog
     * at the user unannounced.
     */
    suspend fun captureWithFallback(allowMediaProjection: Boolean): CaptureOutcome {
        val attempts = mutableListOf<CaptureAttempt>()

        // --- 2. Accessibility screenshot -------------------------------------
        if (AccessibilityCaptureService.isConnected()) {
            when (val result = AccessibilityCaptureService.captureDisplay(context)) {
                is CaptureResult.Success -> {
                    attempts += CaptureAttempt(CaptureSource.ACCESSIBILITY, "ok")
                    return finish(result, attempts)
                }
                is CaptureResult.Failed -> {
                    attempts += CaptureAttempt(CaptureSource.ACCESSIBILITY, result.reason)
                    Log.w(TAG, "Accessibility capture failed: ${result.reason}")
                }
                is CaptureResult.Unavailable -> {
                    attempts += CaptureAttempt(CaptureSource.ACCESSIBILITY, result.reason)
                }
                CaptureResult.Denied -> {
                    attempts += CaptureAttempt(CaptureSource.ACCESSIBILITY, "denied")
                }
            }
        } else {
            attempts += CaptureAttempt(CaptureSource.ACCESSIBILITY, "service not enabled")
        }

        // --- 3. MediaProjection ----------------------------------------------
        if (allowMediaProjection) {
            attempts += CaptureAttempt(
                CaptureSource.MEDIA_PROJECTION,
                "requires an explicit consent dialog in the UI"
            )
        }

        return finish(CaptureResult.Unavailable("No capture mechanism is available"), attempts)
    }

    private fun finish(result: CaptureResult, attempts: List<CaptureAttempt>): CaptureOutcome {
        _lastAttempts.value = attempts
        return CaptureOutcome(result, attempts)
    }

    private companion object {
        const val TAG = "Lenshift/Capture"
    }
}
