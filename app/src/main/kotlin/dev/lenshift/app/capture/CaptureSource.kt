package dev.lenshift.app.capture

/**
 * Where the pixels on screen came from. Surfaced in the UI and in diagnostics so the
 * user always knows which mechanism produced their screenshot.
 */
enum class CaptureSource(val id: String, val label: String) {
    /** Bitmap handed to `VoiceInteractionSession.onHandleScreenshot`. Preferred. */
    ASSIST_SCREENSHOT("assist_screenshot", "Assistant screenshot"),

    /** `AccessibilityService.takeScreenshot()`, user-enabled fallback. */
    ACCESSIBILITY("accessibility", "Accessibility screenshot"),

    /** `MediaProjection`, always preceded by an explicit consent dialog. */
    MEDIA_PROJECTION("media_projection", "Screen capture"),

    /** An image the user shared into Lenshift from another app. */
    SHARED_IMAGE("shared_image", "Shared image"),

    /** Nothing was captured; the UI must degrade to text-only input. */
    NONE("none", "No capture");

    companion object {
        fun fromId(id: String?): CaptureSource =
            entries.firstOrNull { it.id == id } ?: NONE
    }
}
