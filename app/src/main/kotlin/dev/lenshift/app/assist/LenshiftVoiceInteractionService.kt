package dev.lenshift.app.assist

import android.content.Intent
import android.os.Bundle
import android.service.voice.VoiceInteractionService
import android.service.voice.VoiceInteractionSession
import android.util.Log
import dev.lenshift.app.activation.AssistantRole

/**
 * The [VoiceInteractionService] that makes Lenshift eligible to be the device's
 * digital assistant.
 *
 * What the platform does with this class:
 *  - `VoiceInteractionService.isActiveService()` is true only while Lenshift holds
 *    `RoleManager.ROLE_ASSISTANT`.
 *  - While active, SystemUI routes the assist gesture / long-press-home / assist button
 *    to `showSession(bundle, flags)`.
 *  - When inactive, nothing is bound and no assist data is delivered. The class is
 *    otherwise inert.
 *
 * What this class deliberately does NOT do:
 *  - No speech recognition service, no hotword detector, no always-on microphone.
 *  - No session pre-warming: the session is created on demand by the framework.
 */
class LenshiftVoiceInteractionService : VoiceInteractionService() {

    override fun onReady() {
        super.onReady()
        running = this
        Log.i(TAG, "Lenshift assistant service ready (role held: ${AssistantRole.isHeld(this)})")
    }

    override fun onShutdown() {
        Log.i(TAG, "Lenshift assistant service shutting down")
        if (running === this) running = null
        super.onShutdown()
    }

    /**
     * Called before the framework shows a session. We do not need to prepare anything,
     * but recording the show source here is what lets the diagnostics screen explain
     * which physical trigger actually reached us.
     */
    override fun onPrepareToShowSession(args: Bundle, showFlags: Int) {
        super.onPrepareToShowSession(args, showFlags)
        AssistTriggerLog.recordShow(this, showFlags, args)
    }

    override fun onShowSessionFailed(args: Bundle) {
        Log.w(TAG, "Platform failed to show the Lenshift session")
        super.onShowSessionFailed(args)
    }

    override fun onLaunchVoiceAssistFromKeyguard() {
        // Keyguard launch is declared unsupported in voice_interaction_service.xml.
        // If an OEM calls it anyway, fall back to a plain assist intent.
        Log.i(TAG, "Keyguard assist launch requested")
        runCatching {
            startActivity(
                Intent(this, dev.lenshift.app.ui.AssistLaunchActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            )
        }
    }

    companion object {
        private const val TAG = "Lenshift/VIS"

        /** The bound service while Lenshift is the active assistant; null otherwise. */
        @Volatile
        private var running: LenshiftVoiceInteractionService? = null

        /**
         * Starts an assist session as if the user had triggered the assistant, so the
         * system delivers a fresh screenshot of whatever is on screen now. Returns false
         * when Lenshift is not the active assistant or the platform refuses.
         */
        fun requestScreenshot(): Boolean {
            val service = running ?: return false
            return runCatching {
                service.showSession(
                    Bundle(),
                    VoiceInteractionSession.SHOW_WITH_ASSIST or VoiceInteractionSession.SHOW_WITH_SCREENSHOT
                )
                true
            }.onFailure { Log.w(TAG, "Could not start an assist session", it) }.getOrDefault(false)
        }
    }
}
