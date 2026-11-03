package dev.lenshift.app.assist

/**
 * Per-invocation state for [LenshiftSession].
 *
 * `VoiceInteractionManagerService` reuses a **single** `VoiceInteractionSession` instance
 * for every activation, so all state here is reset by [beginInvocation] at the start of
 * each one. This exists as a separate class for one reason: the failure it prevents is
 * silent. When the `launched` guard was not reset, the assistant kept showing the session
 * and receiving screenshots while never opening the UI, which looks identical to the
 * assistant being broken. A silent failure needs a test, and a test needs logic that does
 * not require a device.
 */
class SessionInvocationState {

    /** Set once the selection UI has been launched for the current invocation. */
    @Volatile
    var launched: Boolean = false

    /** True when a bitmap arrived for the current invocation. */
    @Volatile
    var hasScreenshot: Boolean = false

    /** Text extracted from `AssistStructure`, if the platform provided any. */
    @Volatile
    var assistText: String? = null

    /** Host or app label of the captured screen, used only for display. */
    @Volatile
    var assistAppLabel: String? = null

    /** Clears everything, ready for a new activation. */
    fun beginInvocation() {
        launched = false
        hasScreenshot = false
        assistText = null
        assistAppLabel = null
    }

    /**
     * Claims the single launch slot for this invocation.
     *
     * @return true the first time, false for any later call within the same invocation,
     *         so the UI cannot be opened twice by a screenshot and assist payload racing.
     */
    fun claimLaunch(): Boolean {
        if (launched) return false
        launched = true
        return true
    }

    fun recordScreenshot(present: Boolean) {
        hasScreenshot = present
    }

    /** Releases everything, including the reference to any captured text. */
    fun clear() {
        launched = false
        hasScreenshot = false
        assistText = null
        assistAppLabel = null
    }
}
