package dev.lenshift.app.capture

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import dev.lenshift.app.assist.AssistTriggerLog
import dev.lenshift.app.assist.CaptureHandoff
import dev.lenshift.app.ui.CaptureActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Foreground service that owns one MediaProjection capture end to end.
 *
 * Two platform rules shape this class:
 *
 *  1. **Android 14+ requires a `mediaProjection` foreground service to be running before
 *     `getMediaProjection()` is called.** Without it the call throws `SecurityException`.
 *     An earlier version declared this service but never started it, so every
 *     "Capture screen once" attempt failed on Android 14+.
 *
 *  2. **The capture must not happen while Lenshift is on screen.** A virtual display
 *     mirrors whatever is visible. Capturing from inside the selection activity produced a
 *     picture of Lenshift's own "no screenshot" panel instead of the app the user wanted.
 *
 * So the flow is: the consent activity finishes (taking Lenshift off screen), this service
 * goes foreground, waits for the underlying app to redraw, grabs exactly one frame, hands
 * it to [CaptureActivity] out of band, and stops itself. The notification exists only for
 * that window.
 */
class ScreenCaptureService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Must be first: the platform requires startForeground within a few seconds of
        // startForegroundService, and before getMediaProjection on Android 14+.
        if (!startAsForeground()) {
            stopSelf(startId)
            return START_NOT_STICKY
        }

        scope.launch {
            // Let the consent dialog and Lenshift's own window finish animating out, so
            // the virtual display mirrors the app the user was looking at.
            delay(SETTLE_MS)

            val (width, height, densityDpi) = realDisplaySize()
            val result = MediaProjectionCapture.capture(
                context = this@ScreenCaptureService,
                width = width,
                height = height,
                densityDpi = densityDpi
            )

            val ok = result is CaptureResult.Success
            if (result is CaptureResult.Success) {
                Log.i(TAG, "captured ${result.bitmap.width}x${result.bitmap.height}")
                CaptureHandoff.offer(result.bitmap)
            }
            AssistTriggerLog.recordCapture(this@ScreenCaptureService, "media_projection", ok)
            if (!ok) Log.w(TAG, "MediaProjection capture failed: $result")

            // Return to the selection surface either way: with the frame on success, or
            // with the explanatory "no screenshot" state on failure.
            val open = CaptureActivity.intentFor(
                context = this@ScreenCaptureService,
                source = if (ok) CaptureSource.MEDIA_PROJECTION else CaptureSource.NONE
            )
            runCatching { startActivity(open) }
                .onFailure { Log.e(TAG, "Could not reopen the selection UI", it) }

            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf(startId)
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        // Never leave a consent token behind if the service dies mid-capture.
        MediaProjectionCapture.clearConsent()
        super.onDestroy()
    }

    /**
     * The physical panel size, including system bars.
     *
     * A Service's `resources.displayMetrics` reports the app-usable area, which excludes the
     * status and navigation bars. Sizing the virtual display from it produced a frame that
     * was smaller than the screen and letterboxed inside it, so selection coordinates no
     * longer matched what the user saw. WindowMetrics gives the true bounds.
     */
    private fun realDisplaySize(): Triple<Int, Int, Int> {
        val density = resources.displayMetrics.densityDpi
        val wm = getSystemService(android.view.WindowManager::class.java)
        val bounds = wm?.maximumWindowMetrics?.bounds
        return if (bounds != null && bounds.width() > 0 && bounds.height() > 0) {
            Triple(bounds.width(), bounds.height(), density)
        } else {
            val m = resources.displayMetrics
            Triple(m.widthPixels, m.heightPixels, density)
        }
    }

    /** @return false when the platform refused to promote the service. */
    private fun startAsForeground(): Boolean {
        val manager = getSystemService(NotificationManager::class.java)
        manager?.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "Screen capture",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shown only while Lenshift is capturing one frame"
                setShowBadge(false)
            }
        )

        val notification: Notification = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Lenshift")
            .setContentText("Capturing one screenshot…")
            .setSmallIcon(dev.lenshift.app.R.drawable.ic_tile)
            .setOngoing(true)
            .build()

        return runCatching {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            )
        }.onFailure {
            Log.e(TAG, "Could not enter the foreground for screen capture", it)
        }.isSuccess
    }

    companion object {
        private const val CHANNEL_ID = "lenshift_capture"
        private const val NOTIFICATION_ID = 4201
        private const val TAG = "Lenshift/CaptureService"

        /** Time for Lenshift and the consent dialog to leave the screen. */
        private const val SETTLE_MS = 450L

        /**
         * Starts a capture. Call only after [MediaProjectionCapture.recordConsent] has
         * stored a granted token, and after Lenshift's own UI has been finished.
         */
        fun start(context: Context) {
            val intent = Intent(context, ScreenCaptureService::class.java)
            runCatching { context.startForegroundService(intent) }
                .onFailure { Log.w(TAG, "Could not start capture service", it) }
        }
    }
}
