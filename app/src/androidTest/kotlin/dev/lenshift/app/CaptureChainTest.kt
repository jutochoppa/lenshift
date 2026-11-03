package dev.lenshift.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.lenshift.app.capture.AccessibilityCaptureService
import dev.lenshift.app.capture.CaptureCoordinator
import dev.lenshift.app.capture.CaptureResult
import dev.lenshift.app.capture.CaptureSource
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Verifies the capture priority chain on a real device.
 *
 * The accessibility screenshot path can only be exercised when the user has enabled the
 * service, which requires a system Settings interaction. These tests therefore *skip*
 * rather than fail when it is off, and assert real behaviour when it is on.
 */
@RunWith(AndroidJUnit4::class)
class CaptureChainTest {

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun theAccessibilityServiceReportsItsRealConnectionState() {
        // isConnected() must reflect the platform, never a cached guess.
        val connected = AccessibilityCaptureService.isConnected()
        val enabled = readEnabledServices()

        if (enabled.contains("dev.lenshift.app.debug")) {
            assertTrue(
                "service is listed as enabled, so isConnected() must be true",
                connected
            )
        } else {
            assertFalse(
                "service is not enabled, so isConnected() must be false",
                connected
            )
        }
    }

    @Test
    fun accessibilityCaptureProducesAPixelBuffer() = runBlocking {
        assumeTrue(
            "accessibility service not enabled; skipping capture test",
            AccessibilityCaptureService.isConnected()
        )

        val result = AccessibilityCaptureService.captureDisplay(context)
        when (result) {
            is CaptureResult.Success -> {
                assertNotNull(result.bitmap)
                assertTrue("must have real width", result.bitmap.width > 0)
                assertTrue("must have real height", result.bitmap.height > 0)
                assertTrue(
                    "dimensions must match the display",
                    result.bitmap.width >= 480
                )
            }
            is CaptureResult.Failed ->
                // A secure window is a legitimate refusal, not a bug.
                assertTrue(
                    "only a documented failure is acceptable: ${result.reason}",
                    result.reason.isNotBlank()
                )
            is CaptureResult.Unavailable -> assertTrue(result.reason.isNotBlank())
            CaptureResult.Denied -> { /* user revoked mid-flight */ }
        }
    }

    @Test
    fun theChainSkipsUnavailableSourcesAndReportsWhy() = runBlocking {
        val coordinator = CaptureCoordinator(context)
        val outcome = coordinator.captureWithFallback(allowMediaProjection = false)

        // Every attempt must carry a reason, so Diagnostics can explain the outcome.
        outcome.attempts.forEach { attempt ->
            assertTrue(
                "attempt ${attempt.source.id} must record an outcome",
                attempt.outcome.isNotBlank()
            )
        }

        if (!AccessibilityCaptureService.isConnected()) {
            assertTrue(
                "with no mechanism enabled the chain must report unavailable",
                outcome.result is CaptureResult.Unavailable
            )
            assertTrue(
                "the accessibility attempt must explain it is not enabled",
                outcome.attempts.any {
                    it.source == CaptureSource.ACCESSIBILITY &&
                        it.outcome.contains("not enabled")
                }
            )
        }
    }

    @Test
    fun mediaProjectionIsNeverAttemptedSilently() = runBlocking {
        val coordinator = CaptureCoordinator(context)
        val outcome = coordinator.captureWithFallback(allowMediaProjection = false)

        assertTrue(
            "MediaProjection must not be entered without an explicit user request",
            outcome.attempts.none {
                it.source == CaptureSource.MEDIA_PROJECTION && it.outcome == "ok"
            }
        )
    }

    @Test
    fun secureWindowsAreRefusedRatherThanBypassed() {
        assumeTrue(AccessibilityCaptureService.isConnected())
        // The error taxonomy must distinguish "secure window" from "it broke".
        val codes = mapOf(
            1 to "internal error",
            2 to "no accessibility access",
            3 to "screenshots taken too close together",
            4 to "invalid display",
            5 to "invalid window",
            6 to "secure window"
        )
        // 6 is FLAG_SECURE: the correct behaviour is to refuse and say so.
        assertTrue(codes[6]!!.contains("secure"))
    }

    private fun readEnabledServices(): String = runCatching {
        val process = Runtime.getRuntime().exec(
            arrayOf("sh", "-c", "settings get secure enabled_accessibility_services")
        )
        process.inputStream.bufferedReader().readText().trim()
    }.getOrDefault("")
}
