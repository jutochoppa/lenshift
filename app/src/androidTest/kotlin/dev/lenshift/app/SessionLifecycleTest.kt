package dev.lenshift.app

import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.lenshift.app.assist.CaptureHandoff
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Regression tests for the assist handoff contract.
 *
 * ## The bug these guard against
 *
 * `VoiceInteractionManagerService` reuses **one** `VoiceInteractionSession` instance for
 * every activation. The first implementation set a `launched` guard that `onShow` never
 * reset, so after the first assist gesture the session would show, receive a screenshot,
 * and then silently do nothing — no UI, no error, no log. It looked exactly like the
 * assistant had stopped working.
 *
 * The fix resets all per-invocation state in `onShow`. These tests pin down the handoff
 * behaviour that reset relies on, so a future refactor cannot quietly reintroduce the
 * "second activation does nothing" class of bug.
 */
@RunWith(AndroidJUnit4::class)
class SessionLifecycleTest {

    @Before
    fun setUp() {
        CaptureHandoff.discard()
    }

    @After
    fun tearDown() {
        CaptureHandoff.discard()
    }

    @Test
    fun theHandoffIsSingleShot() {
        CaptureHandoff.offer(solidBitmap())

        assertNotNull("first take must succeed", CaptureHandoff.take())
        assertNull(
            "second take must be empty so a stale bitmap cannot leak into a new question",
            CaptureHandoff.take()
        )
    }

    @Test
    fun aNewOfferReplacesThePreviousOne() {
        CaptureHandoff.offer(solidBitmap(Color.RED))
        CaptureHandoff.offer(solidBitmap(Color.GREEN))

        val taken = CaptureHandoff.take()
        assertNotNull(taken)
        assertEquals(
            "the newest activation must win, not the oldest screenshot",
            Color.GREEN,
            taken!!.getPixel(0, 0)
        )
    }

    @Test
    fun discardPreventsAStaleScreenshotFromBeingUsed() {
        CaptureHandoff.offer(solidBitmap())
        CaptureHandoff.discard()

        assertNull(
            "a discarded handoff must not resurface in a later activation",
            CaptureHandoff.take()
        )
    }

    @Test
    fun peekDoesNotConsume() {
        CaptureHandoff.offer(solidBitmap())

        assertNotNull("peek must see the pending bitmap", CaptureHandoff.peek())
        assertNotNull("peek must not consume it", CaptureHandoff.peek())
        assertNotNull("take still succeeds after peeking", CaptureHandoff.take())
        assertNull(CaptureHandoff.peek())
    }

    @Test
    fun anEmptyHandoffIsHarmless() {
        assertNull(CaptureHandoff.take())
        assertNull(CaptureHandoff.peek())
        CaptureHandoff.discard()
        assertNull(CaptureHandoff.take())
    }

    @Test
    fun consumingRecordsATimestamp() {
        val before = CaptureHandoff.lastConsumedAt()
        CaptureHandoff.offer(solidBitmap())
        CaptureHandoff.take()

        assertTrue(
            "taking a bitmap must record when it happened, for the trigger log",
            CaptureHandoff.lastConsumedAt() >= before
        )
    }

    private fun solidBitmap(color: Int = Color.BLUE): Bitmap =
        Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }
}
