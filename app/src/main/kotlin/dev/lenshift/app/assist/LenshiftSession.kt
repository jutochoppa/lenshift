package dev.lenshift.app.assist

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.service.voice.VoiceInteractionSession
import android.util.Log
import dev.lenshift.app.capture.CaptureSource
import dev.lenshift.app.ui.CaptureActivity

/**
 * The assist session.
 *
 * Delivery order from the platform is not guaranteed. In practice the framework may call,
 * in any order and possibly only some of:
 *
 *   onShow(args, showFlags)
 *   onHandleScreenshot(bitmap)   — only when SystemUI decides to send one
 *   onHandleAssist(state)        — AssistStructure / AssistContent / Bundle
 *
 * so the session buffers whatever arrives and launches the selection UI on a short
 * debounce once it has either a screenshot or assist data. If the platform never delivers
 * a screenshot (OEM variation, protected content, or a plain ACTION_ASSIST launch), the
 * selection UI still opens and the capture fallback chain runs.
 *
 * **The platform reuses one session instance for every activation**, so all per-invocation
 * state lives in [state] and is reset by [SessionInvocationState.beginInvocation] in
 * [onShow]. Getting this wrong is silent — see the note on that class.
 */
class LenshiftSession(context: Context) : VoiceInteractionSession(context) {

    private val mainHandler = Handler(Looper.getMainLooper())

    private val state = SessionInvocationState()

    /** The bitmap for this invocation only; released as soon as it is handed off. */
    private var screenshot: Bitmap? = null

    private var showFlags: Int = 0

    private val launchRunnable = Runnable { launchSelectionUi() }

    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
        this.showFlags = showFlags

        // Reset everything: the platform hands us the same instance every time.
        state.beginInvocation()
        screenshot = null
        mainHandler.removeCallbacks(launchRunnable)

        AssistTriggerLog.recordSessionShow(context, showFlags)
        scheduleLaunch(SCREENSHOT_GRACE_MS)
    }

    override fun onHandleScreenshot(screenshot: Bitmap?) {
        super.onHandleScreenshot(screenshot)
        if (screenshot == null) {
            Log.w(TAG, "Platform delivered a null screenshot")
            state.recordScreenshot(present = false)
            AssistTriggerLog.recordScreenshot(context, delivered = false)
            scheduleLaunch(0)
            return
        }
        this.screenshot = screenshot
        state.recordScreenshot(present = true)
        AssistTriggerLog.recordScreenshot(context, delivered = true)
        scheduleLaunch(0)
    }

    override fun onHandleAssist(state: AssistState) {
        super.onHandleAssist(state)

        this.state.assistAppLabel = runCatching {
            state.assistContent?.webUri?.host?.takeIf { it.isNotBlank() }
        }.getOrNull()

        this.state.assistText = runCatching {
            AssistTextExtractor.extract(state.assistStructure)
        }.getOrNull()?.takeIf { it.isNotBlank() }

        // Assist data alone is enough to open the UI even if no bitmap arrives.
        scheduleLaunch(SCREENSHOT_GRACE_MS)
    }

    override fun onHide() {
        mainHandler.removeCallbacks(launchRunnable)
        super.onHide()
    }

    override fun onDestroy() {
        mainHandler.removeCallbacks(launchRunnable)
        screenshot = null
        state.clear()
        super.onDestroy()
    }

    private fun scheduleLaunch(delayMs: Long) {
        mainHandler.removeCallbacks(launchRunnable)
        mainHandler.postDelayed(launchRunnable, delayMs)
    }

    private fun launchSelectionUi() {
        // One launch per invocation, no matter how many callbacks arrive.
        if (!state.claimLaunch()) return

        val bitmap = screenshot
        if (bitmap == null && state.assistText == null) {
            // Nothing arrived within the grace window. Still open: the capture fallback
            // chain (accessibility, then MediaProjection) gets its turn.
            Log.i(TAG, "No assist payload; opening UI with fallback capture")
        }

        val intent = CaptureActivity.intentFor(
            context = context,
            source = if (bitmap != null) CaptureSource.ASSIST_SCREENSHOT else CaptureSource.NONE,
            preloadedText = state.assistText,
            sourceLabel = state.assistAppLabel
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)

        if (bitmap != null) {
            // Hand the bitmap over out-of-band; never through the Intent
            // (TransactionTooLarge for a full-resolution screenshot).
            CaptureHandoff.offer(bitmap)
        }

        runCatching { startAssistantActivity(intent) }
            .onFailure {
                Log.w(TAG, "startAssistantActivity failed, falling back to startActivity", it)
                runCatching { context.startActivity(intent) }
            }

        // Close the session shell so the captured app is not left behind a blank assist
        // window; CaptureActivity owns the screen from here.
        mainHandler.postDelayed({ runCatching { hide() } }, SESSION_HIDE_MS)
    }

    private companion object {
        const val TAG = "Lenshift/Session"

        /** How long to wait for the screenshot after assist data arrives. */
        const val SCREENSHOT_GRACE_MS = 220L

        /** Give the activity a beat to become visible before hiding the session. */
        const val SESSION_HIDE_MS = 400L
    }
}
