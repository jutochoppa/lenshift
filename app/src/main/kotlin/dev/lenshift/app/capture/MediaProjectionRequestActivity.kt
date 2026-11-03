package dev.lenshift.app.capture

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import dev.lenshift.app.ui.CaptureActivity

/**
 * Hosts the system MediaProjection consent dialog when the selection UI is not the
 * foreground activity (for example, when capture is triggered from a tile).
 *
 * It records the consent result and forwards straight into [CaptureActivity].
 */
class MediaProjectionRequestActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val request = MediaProjectionCapture.createRequestIntent(this)
        if (request == null) {
            finish()
            return
        }
        startActivityForResult(request, REQUEST)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST) return

        MediaProjectionCapture.recordConsent(resultCode, data)

        if (MediaProjectionCapture.hasConsent()) {
            startActivity(
                CaptureActivity.intentFor(this, CaptureSource.MEDIA_PROJECTION)
            )
        }
        finish()
    }

    companion object {
        private const val REQUEST = 2001

        fun intent(context: Context): Intent =
            Intent(context, MediaProjectionRequestActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}
