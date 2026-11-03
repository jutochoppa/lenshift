package dev.lenshift.app.capture

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicReference

/**
 * Last-resort capture through [MediaProjection].
 *
 * Android 14+ requires a fresh user consent token for every capture session, so this
 * class never caches or reuses a projection: each `capture()` call creates a virtual
 * display, grabs exactly one frame, and releases everything. Tokens are held only for
 * the lifetime of that single frame and are dropped immediately afterwards.
 */
object MediaProjectionCapture {

    private const val TAG = "Lenshift/MP"
    private const val TIMEOUT_MS = 5_000L

    private val mutex = Mutex()

    /** A consent token produced by the system dialog, valid for one capture. */
    @Volatile
    private var pendingConsent: Pair<Int, Intent>? = null

    fun createRequestIntent(context: Context): Intent? {
        val manager = context.getSystemService(MediaProjectionManager::class.java) ?: return null
        return manager.createScreenCaptureIntent()
    }

    /** Store the result the system handed back to the consent activity. */
    fun recordConsent(resultCode: Int, data: Intent?) {
        pendingConsent = if (resultCode == Activity.RESULT_OK && data != null) {
            resultCode to data
        } else {
            null
        }
    }

    fun hasConsent(): Boolean = pendingConsent != null

    fun clearConsent() {
        pendingConsent = null
    }

    /**
     * Performs one capture using (and then discarding) the recorded consent.
     *
     * @param width  target pixel width of the virtual display.
     * @param height target pixel height of the virtual display.
     * @param densityDpi display density used to size the virtual display.
     */
    suspend fun capture(
        context: Context,
        width: Int,
        height: Int,
        densityDpi: Int
    ): CaptureResult = mutex.withLock {
        val consent = pendingConsent
        if (consent == null) {
            return@withLock CaptureResult.Unavailable("Screen capture consent was not granted")
        }
        pendingConsent = null

        val manager = context.getSystemService(MediaProjectionManager::class.java)
            ?: return@withLock CaptureResult.Unavailable("MediaProjection is not available")

        val projection = runCatching {
            manager.getMediaProjection(consent.first, consent.second)
        }.getOrNull() ?: return@withLock CaptureResult.Failed("Could not start screen capture")

        // A single-frame reader is enough; we never record.
        val reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 1)
        var virtualDisplay: VirtualDisplay? = null
        val released = AtomicReference(false)

        fun release() {
            if (!released.compareAndSet(false, true)) return
            runCatching { virtualDisplay?.release() }
            runCatching { reader.close() }
            runCatching { projection.stop() }
        }

        // Android 14+ requires a callback to be registered before createVirtualDisplay;
        // without one the call throws IllegalStateException and every capture fails.
        // Registering it also gives us the only notification that the user revoked the
        // projection from the system UI, which must not be swallowed as a generic failure.
        val revoked = AtomicReference<String?>(null)
        runCatching {
            projection.registerCallback(
                object : MediaProjection.Callback() {
                    override fun onStop() {
                        revoked.set("Screen capture was stopped")
                        release()
                    }
                },
                Handler(Looper.getMainLooper())
            )
        }.onFailure {
            Log.w(TAG, "Could not register the MediaProjection callback", it)
        }

        return@withLock try {
            virtualDisplay = projection.createVirtualDisplay(
                "lenshift-capture",
                width,
                height,
                densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                reader.surface,
                null,
                null
            )

            val bitmap = withTimeoutOrNull(TIMEOUT_MS) {
                awaitSingleFrame(reader)
            }

            when {
                revoked.get() != null ->
                    CaptureResult.Unavailable(revoked.get()!!)
                bitmap == null ->
                    CaptureResult.Failed("Screen capture produced no frame")
                else ->
                    CaptureResult.Success(bitmap, CaptureSource.MEDIA_PROJECTION)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "MediaProjection capture failed", t)
            CaptureResult.Failed("Screen capture failed: ${t.message}")
        } finally {
            release()
        }
    }

    private suspend fun awaitSingleFrame(reader: ImageReader): Bitmap? {
        val deferred = CompletableDeferred<Bitmap?>()

        reader.setOnImageAvailableListener({ r ->
            val image = runCatching { r.acquireLatestImage() }.getOrNull()
            if (image != null) {
                val bitmap = runCatching {
                    val plane = image.planes[0]
                    val buffer = plane.buffer
                    val pixelStride = plane.pixelStride
                    val rowStride = plane.rowStride
                    val rowPadding = rowStride - pixelStride * image.width
                    val bmpWidth = image.width + rowPadding / pixelStride

                    val bmp = Bitmap.createBitmap(bmpWidth, image.height, Bitmap.Config.ARGB_8888)
                    bmp.copyPixelsFromBuffer(buffer)
                    // Trim the stride padding so coordinates match the real display.
                    if (bmpWidth != image.width) {
                        Bitmap.createBitmap(bmp, 0, 0, image.width, image.height)
                            .also { bmp.recycle() }
                    } else {
                        bmp
                    }
                }.getOrNull()
                runCatching { image.close() }
                deferred.complete(bitmap)
            }
        }, null)

        return deferred.await()
    }

    /** True when the platform supports the modern per-session consent flow. */
    fun requiresPerSessionConsent(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE
}
