package dev.lenshift.app.assist

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for the per-invocation reset that `LenshiftSession.onShow` performs.
 *
 * The platform hands the *same* session object to every activation, so any state left
 * over from the previous invocation is a correctness bug. The original implementation
 * kept a `launched` guard that was set on the first launch and never cleared, which meant
 * every activation after the first showed the session, received a screenshot, and then
 * silently did nothing.
 *
 * The logic is extracted into [SessionInvocationState] precisely so it can be tested
 * without a device: the failure mode was silent, and a silent failure needs a test.
 */
class SessionInvocationStateTest {

    @Test
    fun aFreshStateIsNotLaunched() {
        val state = SessionInvocationState()
        assertFalse("a new invocation must be launchable", state.launched)
    }

    @Test
    fun beginningAnInvocationClearsTheLaunchedGuard() {
        val state = SessionInvocationState()
        state.launched = true
        state.beginInvocation()
        assertFalse(
            "onShow must clear the guard or the second assist does nothing",
            state.launched
        )
    }

    @Test
    fun beginningAnInvocationDropsThePreviousScreenshot() {
        val state = SessionInvocationState()
        state.hasScreenshot = true
        state.assistText = "old text"
        state.assistAppLabel = "old.host"

        state.beginInvocation()

        assertFalse(
            "a screenshot from the previous activation must never be reused",
            state.hasScreenshot
        )
        assertEquals(null, state.assistText)
        assertEquals(null, state.assistAppLabel)
    }

    @Test
    fun launchingIsIdempotentWithinOneInvocation() {
        val state = SessionInvocationState()
        state.beginInvocation()

        assertTrue("first claim must win", state.claimLaunch())
        assertFalse(
            "a second claim in the same invocation must be rejected, so the UI is not opened twice",
            state.claimLaunch()
        )
    }

    @Test
    fun aSecondInvocationCanLaunchAgain() {
        val state = SessionInvocationState()

        state.beginInvocation()
        assertTrue(state.claimLaunch())

        // The user triggers the assistant again; the platform reuses this session.
        state.beginInvocation()
        assertTrue(
            "the second activation must be able to launch, which is the whole bug",
            state.claimLaunch()
        )
    }

    @Test
    fun manyConsecutiveInvocationsAllLaunch() {
        val state = SessionInvocationState()
        repeat(25) { index ->
            state.beginInvocation()
            assertTrue(
                "invocation ${index + 1} must launch",
                state.claimLaunch()
            )
        }
    }

    @Test
    fun aScreenshotMarksTheState() {
        val state = SessionInvocationState()
        state.beginInvocation()
        state.recordScreenshot(present = true)
        assertTrue(state.hasScreenshot)
    }

    @Test
    fun aNullScreenshotDoesNotMarkTheState() {
        val state = SessionInvocationState()
        state.beginInvocation()
        state.recordScreenshot(present = false)
        assertFalse(
            "a null screenshot must not be treated as a usable payload",
            state.hasScreenshot
        )
    }

    @Test
    fun destroyClearsEverything() {
        val state = SessionInvocationState()
        state.beginInvocation()
        state.claimLaunch()
        state.recordScreenshot(present = true)
        state.assistText = "text"

        state.clear()

        assertFalse(state.launched)
        assertFalse(state.hasScreenshot)
        assertEquals(null, state.assistText)
    }
}
