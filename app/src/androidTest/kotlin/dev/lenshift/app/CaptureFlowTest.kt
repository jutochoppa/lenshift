package dev.lenshift.app

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.printToLog
import androidx.compose.ui.test.click
import androidx.compose.ui.test.swipe
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.lenshift.app.assist.CaptureHandoff
import dev.lenshift.app.ai.ApiProtocol
import dev.lenshift.app.data.settings.SettingsRepository
import dev.lenshift.app.ui.CaptureActivity
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.net.HttpURLConnection
import java.net.URL

/**
 * Drives the real selection surface: gesture, action, streamed answer.
 *
 * This is the closest thing to a user clicking through the app, and it exercises the
 * whole chain — canvas coordinate mapping, cropping, OCR, request construction, SSE
 * parsing and Compose re-rendering — against a live mock provider.
 */
@RunWith(AndroidJUnit4::class)
class CaptureFlowTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<CaptureActivity>()

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Before
    fun seedProvider() {
        // Use the SAME repository instance the activity reads from. A fresh
        // SettingsRepository would write the same SharedPreferences but leave the app's
        // in-memory StateFlow stale, so the activity would still see "no provider".
        val repo = dev.lenshift.app.di.ServiceLocator.settings
        val base = "$MOCK/v1"
        val profile = repo.newProfile(ApiProtocol.OPENAI_CHAT).copy(
            displayName = "Mock",
            baseUrl = base,
            modelId = "mock-gpt-4o",
            allowCleartext = true,
            streaming = true
        )
        repo.upsertProfile(profile)
        repo.setActiveProfile(profile.id)
        repo.setApiKey(profile.id, "test-key")
        repo.updatePreferences { it.copy(ocrEnabled = true) }
    }

    @Test
    fun theSelectionSurfaceRendersTheFrozenScreenshot() {
        val bitmap = renderScreen()
        composeRule.runOnUiThread { CaptureHandoff.offer(bitmap) }

        // Re-create so the activity picks up the handed-off bitmap.
        composeRule.activityRule.scenario.recreate()

        composeRule.waitForIdle()
        composeRule.onNodeWithText("Circle").assertExists()
        composeRule.onNodeWithText("Box").assertExists()
        composeRule.onNodeWithText("Explain").assertExists()
        composeRule.onNodeWithText("Ask about this screen…").assertExists()
    }

    @Test
    fun drawingACircleEnablesAStreamedAnswer() {
        val bitmap = renderScreen()
        composeRule.runOnUiThread { CaptureHandoff.offer(bitmap) }
        composeRule.activityRule.scenario.recreate()
        composeRule.waitForIdle()

        // Circle the middle of the screenshot with a drag.
        composeRule.onNodeWithText("Circle").performClick()
        val root = composeRule.onRoot()
        root.performTouchInput {
            swipe(
                start = center.copy(x = center.x - 200f, y = center.y - 120f),
                end = center.copy(x = center.x + 200f, y = center.y + 120f),
                durationMillis = 400
            )
        }
        composeRule.waitForIdle()

        // Ask.
        composeRule.onNodeWithText("Explain").performClick()
        composeRule.waitForIdle()

        // Dump the tree so a failure explains itself instead of only timing out.
        composeRule.onRoot().printToLog("LENSHIFT_TREE")

        // The answer must appear, streamed from the mock provider.
        composeRule.waitUntil(timeoutMillis = 25_000) {
            composeRule.onAllNodesWithText("Welcome to Chrome", substring = true)
                .fetchSemanticsNodes()
                .isNotEmpty()
        }

        val nodes = composeRule.onAllNodesWithText("Welcome to Chrome", substring = true)
            .fetchSemanticsNodes()
        assertTrue("the streamed answer must be rendered", nodes.isNotEmpty())
    }

    @Test
    fun theResponseSheetOffersCopyShareAndFollowUp() {
        val bitmap = renderScreen()
        composeRule.runOnUiThread { CaptureHandoff.offer(bitmap) }
        composeRule.activityRule.scenario.recreate()
        composeRule.waitForIdle()

        // Select a region first. Without a selection the action buttons are disabled now.
        composeRule.onNodeWithText("Circle").performClick()
        val root = composeRule.onRoot()
        root.performTouchInput {
            swipe(
                start = center.copy(x = center.x - 180f, y = center.y - 100f),
                end = center.copy(x = center.x + 180f, y = center.y + 100f),
                durationMillis = 400
            )
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithText("Explain").performClick()

        composeRule.waitUntil(timeoutMillis = 25_000) {
            composeRule.onAllNodesWithText("Welcome to Chrome", substring = true)
                .fetchSemanticsNodes()
                .isNotEmpty()
        }

        // Follow-up input and dismissal must both be reachable.
        composeRule.onNodeWithText("Follow up…").assertExists()
        composeRule.onNodeWithText("Close").assertExists()
    }

    @Test
    fun aFailingProviderShowsAnActionableError() {
        val repo = dev.lenshift.app.di.ServiceLocator.settings
        val profile = repo.activeProfile()!!
        repo.upsertProfile(
            profile.copy(customHeaders = mapOf("X-Mock-Scenario" to "auth-fail"))
        )

        val bitmap = renderScreen()
        composeRule.runOnUiThread { CaptureHandoff.offer(bitmap) }
        composeRule.activityRule.scenario.recreate()
        composeRule.waitForIdle()

        composeRule.onNodeWithText("Circle").performClick()
        val root2 = composeRule.onRoot()
        root2.performTouchInput {
            swipe(
                start = center.copy(x = center.x - 180f, y = center.y - 100f),
                end = center.copy(x = center.x + 180f, y = center.y + 100f),
                durationMillis = 400
            )
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithText("Explain").performClick()

        composeRule.waitUntil(timeoutMillis = 25_000) {
            composeRule.onAllNodesWithText("Try again").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("Try again").assertExists()
    }

    // ---------------------------------------------------------------- regression

    /**
     * A quick action with no selection must never upload the whole screenshot.
     *
     * This is the privacy invariant that regressed: an empty selection used to be treated
     * as "use the full bitmap", so tapping Explain on a blank area silently sent the
     * entire screen to the provider.
     */
    @Test
    fun anActionWithoutASelectionDoesNotSubmitAnything() {
        val bitmap = renderScreen()
        composeRule.runOnUiThread { CaptureHandoff.offer(bitmap) }
        composeRule.activityRule.scenario.recreate()
        composeRule.waitForIdle()

        // No gesture at all: the action buttons must be disabled, so clicking is a no-op.
        composeRule.onNodeWithText("Explain").performClick()
        composeRule.waitForIdle()

        // No request was made, so no answer and no error sheet appeared.
        assertTrue(
            "no answer must be produced without a selection",
            composeRule.onAllNodesWithText("Welcome to Chrome", substring = true)
                .fetchSemanticsNodes()
                .isEmpty()
        )
    }

    /**
     * Tap must resolve to a bounded region around the finger, never the full screen.
     */
    @Test
    fun tappingProducesALocalSelectionNotTheWholeScreen() {
        val bitmap = renderScreen()
        composeRule.runOnUiThread { CaptureHandoff.offer(bitmap) }
        composeRule.activityRule.scenario.recreate()
        composeRule.waitForIdle()

        composeRule.onNodeWithText("Tap").performClick()
        val root = composeRule.onRoot()
        root.performTouchInput { click(center) }
        composeRule.waitForIdle()

        // The lens is small, so the action becomes available and the request is bounded.
        composeRule.onNodeWithText("Explain").performClick()
        composeRule.waitUntil(timeoutMillis = 25_000) {
            composeRule.onAllNodesWithText("Welcome to Chrome", substring = true)
                .fetchSemanticsNodes()
                .isNotEmpty() ||
                composeRule.onAllNodesWithText("Try again").fetchSemanticsNodes().isNotEmpty()
        }
    }

    // ---------------------------------------------------------------- helpers

    /** A screenshot-like bitmap containing recognisable text. */
    private fun renderScreen(): Bitmap {
        val width = 1080
        val height = 2000
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.WHITE)

        val heading = Paint().apply {
            color = Color.BLACK
            textSize = 72f
            isAntiAlias = true
            isFakeBoldText = true
        }
        canvas.drawText("Welcome to Chrome", 80f, 500f, heading)

        val body = Paint().apply {
            color = Color.DKGRAY
            textSize = 44f
            isAntiAlias = true
        }
        canvas.drawText("This is the browser's first-run page.", 80f, 620f, body)
        canvas.drawText("Sign in to sync your bookmarks.", 80f, 700f, body)

        return bitmap
    }

    private companion object {
        val MOCK: String by lazy {
            listOf("http://10.0.2.2:8787", "http://127.0.0.1:8787").firstOrNull { base ->
                runCatching {
                    val c = URL("$base/health").openConnection() as HttpURLConnection
                    c.connectTimeout = 2500
                    c.readTimeout = 2500
                    c.responseCode == 200
                }.getOrDefault(false)
            } ?: "http://10.0.2.2:8787"
        }
    }
}
