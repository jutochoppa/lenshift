package dev.lenshift.app.assist

import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionService
import android.speech.SpeechRecognizer
import android.util.Log

/**
 * A deliberately inert [RecognitionService].
 *
 * ## Why this class exists
 *
 * `VoiceInteractionManagerService` refuses to activate a VoiceInteractionService whose
 * metadata omits `recognitionService`. On Android 14 the platform logs:
 *
 *     E VoiceInteractionManager: The RecognitionService must be set to avoid boot loop
 *                                on user switch
 *
 * and then declines to install the assistant. That check is unconditional — the AOSP
 * source comment explains it guards against a user switch leaving a half-configured
 * assistant bound. It means **no** third-party assistant can be activated without
 * declaring *some* recognition service, even a voice-free one.
 *
 * Lenshift has no speech feature, so this service does the minimum required to be a valid
 * binding target and then refuses every request with
 * [SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE]. It is declared in the manifest with
 * `android.permission.BIND_RECOGNITION_SERVICE` and is never advertised as the system
 * speech recogniser, never opens the microphone, and never records audio.
 *
 * This is the honest trade-off: a truthful stub beats either (a) pretending Lenshift has
 * speech recognition, or (b) failing to activate at all.
 */
class LenshiftRecognitionService : RecognitionService() {

    override fun onStartListening(intent: Intent?, listener: Callback?) {
        Log.i(TAG, "Speech recognition requested; Lenshift has no speech engine")
        // Report the failure immediately and honestly rather than opening the mic.
        runCatching {
            listener?.error(SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE)
        }
    }

    override fun onStopListening(listener: Callback?) {
        // Nothing was ever listening.
    }

    override fun onCancel(listener: Callback?) {
        // Nothing to cancel.
    }

    override fun onCheckRecognitionSupport(
        intent: Intent,
        attributionSource: android.content.AttributionSource,
        callback: SupportCallback
    ) {
        // Tell callers up front that this engine cannot serve them.
        callback.onError(SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE)
    }

    override fun onTriggerModelDownload(intent: Intent) {
        // No models exist to download.
    }

    private companion object {
        const val TAG = "Lenshift/Recognition"
    }
}
