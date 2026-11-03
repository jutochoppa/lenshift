package dev.lenshift.app.assist

import android.os.Bundle
import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService

/**
 * Creates one [LenshiftSession] per platform-initiated assist interaction.
 *
 * The platform calls [onNewSession] when the assist gesture fires and tears the session
 * down when the user leaves it, so no state outlives a single invocation.
 */
class LenshiftVoiceInteractionSessionService : VoiceInteractionSessionService() {

    override fun onNewSession(args: Bundle?): VoiceInteractionSession =
        LenshiftSession(this)
}
