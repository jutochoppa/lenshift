package dev.lenshift.app.capture

import android.graphics.Bitmap

/**
 * Result of a capture attempt. Explicit variants keep "no pixels" distinguishable from
 * "the user refused" and from "the platform said no", which the UI needs to explain.
 */
sealed interface CaptureResult {

    data class Success(val bitmap: Bitmap, val source: CaptureSource) : CaptureResult

    /** The mechanism is not set up on this device (service off, API too old, ...). */
    data class Unavailable(val reason: String) : CaptureResult

    /** The mechanism is set up but the attempt failed. */
    data class Failed(val reason: String) : CaptureResult

    /** The user declined an explicit consent prompt. */
    data object Denied : CaptureResult

    val isSuccess: Boolean get() = this is Success
}

/** Ordered description of what the coordinator tried, for diagnostics. */
data class CaptureAttempt(val source: CaptureSource, val outcome: String)

data class CaptureOutcome(
    val result: CaptureResult,
    val attempts: List<CaptureAttempt>
)
