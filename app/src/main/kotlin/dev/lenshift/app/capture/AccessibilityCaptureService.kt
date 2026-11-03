package dev.lenshift.app.capture

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.graphics.Bitmap
import android.hardware.HardwareBuffer
import android.os.Build
import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicReference

/**
 * Optional screenshot fallback built on `AccessibilityService.takeScreenshot()`.
 *
 * Scope, deliberately narrow:
 *  - The service requests no accessibility events and no window content.
 *  - `takeScreenshot()` is called only in direct response to a user activation.
 *  - `takeScreenshotOfWindow()` (API 34+) is used when the caller supplies a window id,
 *    so the system can omit overlays such as the Lenshift UI itself.
 *
 * Failure modes are surfaced verbatim: protected windows and secure surfaces produce
 * `ERROR_TAKE_SCREENSHOT_SECURE_WINDOW`, which Lenshift reports rather than retrying.
 */
class AccessibilityCaptureService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance.set(this)
        Log.i(TAG, "Accessibility capture service connected")
    }

    override fun onAccessibilityEvent(event: android.view.accessibility.AccessibilityEvent?) {
        // Intentionally empty. Lenshift subscribes to no event types and reads no
        // window content; this method exists only because the base class requires it.
    }

    override fun onInterrupt() {
        Log.i(TAG, "Accessibility capture service interrupted")
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        instance.compareAndSet(this, null)
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        instance.compareAndSet(this, null)
        super.onDestroy()
    }

    companion object {
        private const val TAG = "Lenshift/A11y"
        private const val TIMEOUT_MS = 4_000L

        private val instance = AtomicReference<AccessibilityCaptureService?>(null)

        fun isConnected(): Boolean = instance.get() != null

        /**
         * Captures the whole display.
         *
         * @return the bitmap, or null when the service is not connected or the platform
         *         refuses the request.
         */
        suspend fun captureDisplay(context: Context): CaptureResult {
            val service = instance.get()
                ?: return CaptureResult.Unavailable("Accessibility service is not enabled")
            return service.takeScreenshotInternal(windowId = null)
        }

        /**
         * Captures a single window (API 34+), which lets the platform exclude overlays.
         */
        suspend fun captureWindow(context: Context, windowId: Int): CaptureResult {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                return CaptureResult.Unavailable("takeScreenshotOfWindow requires Android 14+")
            }
            val service = instance.get()
                ?: return CaptureResult.Unavailable("Accessibility service is not enabled")
            return service.takeScreenshotInternal(windowId = windowId)
        }
    }

    private suspend fun takeScreenshotInternal(windowId: Int?): CaptureResult {
        val deferred = CompletableDeferred<CaptureResult>()
        val executor = java.util.concurrent.Executor { it.run() }

        val callback = object : TakeScreenshotCallback {
            override fun onSuccess(screenshot: ScreenshotResult) {
                val bitmap = runCatching {
                    val hardwareBuffer: HardwareBuffer = screenshot.hardwareBuffer
                    val bmp = Bitmap.wrapHardwareBuffer(hardwareBuffer, screenshot.colorSpace)
                        ?.copy(Bitmap.Config.ARGB_8888, false)
                    hardwareBuffer.close()
                    bmp
                }.getOrNull()

                if (bitmap == null) {
                    deferred.complete(
                        CaptureResult.Failed("Screenshot could not be decoded")
                    )
                } else {
                    deferred.complete(CaptureResult.Success(bitmap, CaptureSource.ACCESSIBILITY))
                }
            }

            override fun onFailure(errorCode: Int) {
                deferred.complete(CaptureResult.Failed(describeError(errorCode)))
            }
        }

        runCatching {
            if (windowId != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                takeScreenshotOfWindow(windowId, executor, callback)
            } else {
                // The first parameter is a display id, not a capture mode.
                takeScreenshot(android.view.Display.DEFAULT_DISPLAY, executor, callback)
            }
        }.onFailure {
            return CaptureResult.Failed("Screenshot request threw: ${it.message}")
        }

        val result = withTimeoutOrNull(TIMEOUT_MS) { deferred.await() }
            ?: return CaptureResult.Failed("Screenshot timed out")

        return withContext(Dispatchers.Default) { result }
    }

    private fun describeError(code: Int): String = when (code) {
        ERROR_TAKE_SCREENSHOT_INTERNAL_ERROR -> "Internal error while taking the screenshot"
        ERROR_TAKE_SCREENSHOT_NO_ACCESSIBILITY_ACCESS ->
            "Accessibility access was revoked"
        ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT ->
            "Screenshots were requested too quickly"
        ERROR_TAKE_SCREENSHOT_INVALID_DISPLAY ->
            "The requested display is not available"
        ERROR_TAKE_SCREENSHOT_SECURE_WINDOW ->
            "This screen is protected (FLAG_SECURE) and cannot be captured"
        ERROR_TAKE_SCREENSHOT_INVALID_WINDOW ->
            "The requested window is no longer available"
        else -> "Screenshot failed (code $code)"
    }
}
