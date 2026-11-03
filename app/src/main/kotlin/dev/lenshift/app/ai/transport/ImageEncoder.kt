package dev.lenshift.app.ai.transport

import android.graphics.Bitmap
import android.util.Base64
import dev.lenshift.app.ai.ImageDetail
import java.io.ByteArrayOutputStream
import kotlin.math.roundToInt

/**
 * Turns a cropped selection into provider-ready bytes.
 *
 * Rules:
 *  - Never upscale. Upscaling costs tokens and adds no information.
 *  - Downscale only when the longest edge exceeds the profile's budget.
 *  - Default to JPEG because screenshots with photographic content compress far better
 *    than PNG; switch to PNG when the user wants lossless text.
 *  - All work happens on the calling (already off-main) coroutine.
 */
object ImageEncoder {

    data class Encoded(
        val base64: String,
        val mimeType: String,
        val widthPx: Int,
        val heightPx: Int,
        val byteCount: Int
    )

    fun encode(
        bitmap: Bitmap,
        maxEdgePx: Int,
        jpegQuality: Int,
        detail: ImageDetail
    ): Encoded {
        val scaled = downscale(bitmap, maxEdgePx)
        val quality = jpegQuality.coerceIn(40, 100)

        val mime = when (detail) {
            // PNG keeps small UI text crisp; worth the bytes for OCR-style selections.
            ImageDetail.HIGH -> "image/png"
            ImageDetail.AUTO, ImageDetail.LOW -> "image/jpeg"
        }

        val bytes = if (mime == "image/png") {
            ByteArrayOutputStream().use { out ->
                scaled.compress(Bitmap.CompressFormat.PNG, 100, out)
                out.toByteArray()
            }
        } else {
            ByteArrayOutputStream().use { out ->
                scaled.compress(Bitmap.CompressFormat.JPEG, quality, out)
                out.toByteArray()
            }
        }

        return Encoded(
            base64 = Base64.encodeToString(bytes, Base64.NO_WRAP),
            mimeType = mime,
            widthPx = scaled.width,
            heightPx = scaled.height,
            byteCount = bytes.size
        )
    }

    /** Scales down so the longest edge is at most [maxEdgePx]; never enlarges. */
    fun downscale(bitmap: Bitmap, maxEdgePx: Int): Bitmap {
        val longest = maxOf(bitmap.width, bitmap.height)
        if (maxEdgePx <= 0 || longest <= maxEdgePx) return bitmap

        val ratio = maxEdgePx.toFloat() / longest
        val width = (bitmap.width * ratio).roundToInt().coerceAtLeast(1)
        val height = (bitmap.height * ratio).roundToInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(bitmap, width, height, true)
    }

    /** Rough token estimate so the UI can warn before a huge upload. */
    fun estimateTokens(widthPx: Int, heightPx: Int): Int {
        // ~ (w*h)/750 is the widely used heuristic for image token cost.
        return ((widthPx.toLong() * heightPx) / 750L).toInt().coerceAtLeast(85)
    }
}
