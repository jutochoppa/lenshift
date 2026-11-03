package dev.lenshift.app.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import dev.lenshift.app.assist.AssistTriggerLog
import dev.lenshift.app.assist.CaptureHandoff
import dev.lenshift.app.capture.CaptureSource
import dev.lenshift.app.data.settings.AppSettings
import dev.lenshift.app.di.ServiceLocator
import kotlinx.coroutines.launch

/**
 * The single entry point every non-assistant activation uses (tile, launcher shortcut,
 * accessibility shortcut, ACTION_ASSIST when the platform routes it here).
 *
 * It resolves the best available capture mechanism and forwards straight to
 * [CaptureActivity] without showing any UI of its own, so activation feels immediate.
 *
 * This activity is transparent and finishes itself as soon as forwarding succeeds.
 */
class AssistLaunchActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val mechanism = intent?.getStringExtra(EXTRA_MECHANISM) ?: "unknown"
        AssistTriggerLog.recordActivation(this, mechanism)
        Log.i(TAG, "Activation via $mechanism")

        // An assist payload may already be waiting if the platform delivered one.
        val pending = CaptureHandoff.take()

        lifecycleScope.launch {
            forward(mechanism, pending)
        }
    }

    private suspend fun forward(mechanism: String, pending: android.graphics.Bitmap?) {
        val settings = ServiceLocator.settings.current

        // Accessibility is the only fallback that needs no consent dialog, so prefer it.
        if (pending == null && dev.lenshift.app.capture.AccessibilityCaptureService.isConnected()) {
            val result = dev.lenshift.app.capture.AccessibilityCaptureService.captureDisplay(this)
            if (result is dev.lenshift.app.capture.CaptureResult.Success) {
                CaptureHandoff.offer(result.bitmap)
                AssistTriggerLog.recordCapture(this, "accessibility", ok = true)
                launchCapture(CaptureSource.ACCESSIBILITY, mechanism)
                return
            }
            AssistTriggerLog.recordCapture(this, "accessibility", ok = false)
            Log.w(TAG, "Accessibility capture failed: $result")
        }

        if (pending != null) {
            CaptureHandoff.offer(pending)
            launchCapture(CaptureSource.ASSIST_SCREENSHOT, mechanism)
            return
        }

        // Nothing captured yet. Open the selection UI anyway: it offers the explicit
        // screen-capture consent button and a manual paste path.
        launchCapture(CaptureSource.NONE, mechanism)
    }

    private fun launchCapture(source: CaptureSource, mechanism: String) {
        val intent = CaptureActivity.intentFor(
            context = this,
            source = source,
            mechanism = mechanism
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)

        runCatching { startActivity(intent) }
            .onFailure { Log.e(TAG, "Could not open the selection UI", it) }

        finish()
        overridePendingTransition(0, 0)
    }

    companion object {
        private const val TAG = "Lenshift/AssistLaunch"
        const val EXTRA_MECHANISM = "dev.lenshift.app.extra.MECHANISM"

        fun intent(context: Context, mechanism: String): Intent =
            Intent(context, AssistLaunchActivity::class.java)
                .putExtra(EXTRA_MECHANISM, mechanism)
    }
}
