package dev.lenshift.app

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performScrollToNode
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.lenshift.app.ui.MainActivity
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Settings navigation.
 *
 * The settings host originally kept only the current screen in a plain `remember`, with no
 * BackHandler. The system Back gesture from any sub-screen therefore exited the activity
 * instead of returning to the parent, which made the settings feel broken: open
 * Diagnostics, press Back, land on the launcher.
 */
@RunWith(AndroidJUnit4::class)
class SettingsNavigationTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun systemBackFromASubScreenReturnsHome() {
        composeRule.waitForIdle()
        // Home is a LazyColumn, so off-screen rows are not composed yet. Scroll the list
        // to the row first; performScrollTo() only works on nodes that already exist.
        composeRule.onNode(hasScrollAction()).performScrollToNode(hasText("Diagnostics"))
        composeRule.onNodeWithText("Diagnostics").performClick()
        composeRule.waitForIdle()

        // We are on Diagnostics, so the Home headline is gone.
        assertTrue(
            "Diagnostics should replace the home screen",
            composeRule.onAllNodesWithText("Circle anything on screen. Ask any model you like.")
                .fetchSemanticsNodes()
                .isEmpty()
        )

        // Dispatch Back exactly as the system gesture does, through the activity's
        // OnBackPressedDispatcher, so the BackHandler under test is the one exercised.
        composeRule.runOnUiThread {
            composeRule.activity.onBackPressedDispatcher.onBackPressed()
        }
        composeRule.waitForIdle()

        // Back returns to Home rather than finishing the activity.
        composeRule.onNodeWithText("Circle anything on screen. Ask any model you like.")
            .assertExists()
    }

    @Test
    fun theShareRowIsInformationalNotAFakeAction() {
        composeRule.waitForIdle()
        composeRule.onNode(hasScrollAction()).performScrollToNode(hasText("Share to Lenshift"))
        composeRule.onNodeWithText("Share to Lenshift").assertExists()
        // The subtitle now explains where sharing actually starts.
        composeRule.onNodeWithText(
            "In any app, tap Share on an image or text and pick Lenshift"
        ).assertExists()
    }
}
