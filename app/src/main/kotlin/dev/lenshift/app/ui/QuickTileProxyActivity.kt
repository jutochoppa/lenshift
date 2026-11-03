package dev.lenshift.app.ui

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import dev.lenshift.app.assist.CaptureHandoff
import dev.lenshift.app.capture.CaptureSource

/**
 * Transparent forwarding activity used when a component must start an activity but has
 * no capture context of its own. Finishes immediately.
 */
class QuickTileProxyActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pending = CaptureHandoff.take()
        startActivity(
            CaptureActivity.intentFor(
                context = this,
                source = if (pending != null) CaptureSource.ASSIST_SCREENSHOT else CaptureSource.NONE
            )
        )
        if (pending != null) CaptureHandoff.offer(pending)
        finish()
    }
}
