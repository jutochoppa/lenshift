package dev.lenshift.app.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.core.content.IntentCompat
import dev.lenshift.app.assist.CaptureHandoff
import dev.lenshift.app.capture.CaptureSource

/**
 * Receives an image or text shared from another app and opens the selection surface.
 *
 * This is the "send to Lenshift" path: from the Photos app, a browser's share sheet, or
 * any app that shares an image, the user picks Lenshift and lands directly on the
 * selection canvas with that image loaded.
 */
class ShareTargetActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val intent = intent ?: run { finish(); return }

        // The type must be explicit: inferred from the null branch, the extra was read as
        // Nothing? and every shared image crashed with a ClassCastException.
        val imageUri: Uri? = when (intent.action) {
            Intent.ACTION_SEND -> IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
            else -> null
        }

        val text = intent.getStringExtra(Intent.EXTRA_TEXT)

        val capture = if (imageUri != null) {
            runCatching {
                contentResolver.openInputStream(imageUri)?.use { stream ->
                    android.graphics.BitmapFactory.decodeStream(stream)
                }
            }.getOrNull()
        } else {
            null
        }

        if (capture != null) CaptureHandoff.offer(capture)

        startActivity(
            CaptureActivity.intentFor(
                context = this,
                source = if (capture != null) CaptureSource.SHARED_IMAGE else CaptureSource.NONE,
                preloadedText = text
            )
        )
        finish()
    }
}
