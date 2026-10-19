package eu.tsernikov.slminiwifi.ui

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import eu.tsernikov.slminiwifi.R
import eu.tsernikov.slminiwifi.state.MiniSnapshot
import eu.tsernikov.slminiwifi.state.MiniState
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "en-rUS-w411dp-h891dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ConfigurationHomeScreenTest {
    @get:Rule val compose = createComposeRule()

    @Test fun readyHomeExplainsBothChoicesAndOnlyOffersSetupAndDetails() {
        var choice: Boolean? = null
        var details = 0
        compose.show(false) {
            HomeScreen(MiniSnapshot(MiniState.SetupPending), onRefresh = {}, onConnect = {},
                onDetails = { details++ }, onSetupChoice = { choice = it }, animateGuides = false,
                onGuide = {}, onHelp = {})
        }
        compose.onNodeWithText("Ready to set up").assertIsDisplayed()
        compose.onNodeWithText("Your Mini has been reset. Choose how it should work.").assertIsDisplayed()
        compose.onNodeWithContentDescription("Mini ready to set up").assertIsDisplayed()
        compose.onNodeWithText("Cable only. No Wi-Fi from the Mini.").assertIsDisplayed()
        compose.onNodeWithText("Your own Wi-Fi name and password.").assertIsDisplayed()
        compose.onAllNodes(hasClickAction()).assertCountEquals(3)
        for (label in listOf("Check again", "Reset help", "Start here",
            "Checking your Mini does not change its settings.")) {
            compose.onNodeWithText(label).assertDoesNotExist()
        }
        compose.onNodeWithText("Wi-Fi off").assertHeightIsAtLeast(64.dp).performClick()
        assertEquals(true, choice)
        compose.onNodeWithText("Wi-Fi on with password").assertHeightIsAtLeast(64.dp).performClick()
        assertEquals(false, choice)
        compose.onNodeWithText("Details").assertHeightIsAtLeast(48.dp).performClick()
        assertEquals(1, details)
    }

    @Test fun configuredHasNoManualCheck() = noCheck(MiniState.Configured)
    @Test fun bypassHasNoManualCheck() = noCheck(MiniState.Bypass)
    @Test fun unknownHasNoManualCheck() = noCheck(MiniState.Unknown)
    @Test fun setupPendingHasNoManualCheck() = noCheck(MiniState.SetupPending)
    @Test fun rebootingHasNoManualCheck() = noCheck(MiniState.Rebooting)

    @Test fun notFoundHasManualRetryWhenCheckingStopped() = retry(false)
    @Test fun timedOutHasManualRetryWhenCheckingStopped() = retry(true)

    @Test fun notFoundHasNoManualCheckDuringInitialProbe() {
        compose.show(false) {
            HomeScreen(MiniSnapshot(MiniState.NotFound), isChecking = true,
                onRefresh = {}, onConnect = {}, onDetails = {}, onSetupChoice = {}, animateGuides = false)
        }
        compose.onNodeWithText("Check again").assertDoesNotExist()
    }

    @Test fun guideCopyQuotesTheNewReadinessTitle() {
        val resources = RuntimeEnvironment.getApplication().resources
        assertTrue(resources.getString(R.string.reset_ready).contains("“Ready to set up”"))
        assertTrue(resources.getString(R.string.field_check_body).contains("“Ready to set up”"))
        assertEquals("Mini ready to set up", resources.getString(R.string.ready_icon_description))
    }

    @Test fun resetGuideShowsRestartProgressWithoutManualRetry() {
        compose.show(false) {
            ResetGuideScreen({}, animateGuides = false, state = MiniState.Rebooting)
        }
        compose.onNodeWithText("Waiting for your Mini to restart…").assertIsDisplayed()
        compose.onNodeWithText("Waiting for the Mini to answer").assertIsDisplayed()
        compose.onNodeWithText("This can take up to 1 minute. If it takes longer, try checking again.")
            .assertDoesNotExist()
        compose.onNodeWithText("Check again").assertDoesNotExist()
    }

    @Test fun configuredResetGuideHasNoManualRetryWhileAutoChecking() {
        compose.show(false) { ResetGuideScreen({}, animateGuides = false, state = MiniState.Configured) }
        compose.onNodeWithText("Check again").assertDoesNotExist()
        compose.onNodeWithText("Back").assertIsDisplayed()
    }

    @Test fun homeRebootShowsRestartProgressAndRecoveryTime() {
        compose.show(false) {
            HomeScreen(MiniSnapshot(MiniState.Rebooting), onRefresh = {}, onConnect = {}, onDetails = {},
                onSetupChoice = {}, animateGuides = false)
        }
        compose.onNodeWithText("Waiting for your Mini to restart…").assertIsDisplayed()
        compose.onNodeWithText("Waiting for the Mini to answer").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("We will check every 3 seconds for up to 3 minutes. If it still does not answer, we will show you how to reconnect.")
            .performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Check again").assertDoesNotExist()
    }

    @Test fun restartProgressBecomesVisibleWhileReadingBottomOfResetGuide() {
        val state = androidx.compose.runtime.mutableStateOf(MiniState.Configured)
        compose.show(false) { ResetGuideScreen({}, animateGuides = false, state = state.value) }
        compose.onNodeWithText("The Mini is ready to set up when this app says", substring = true)
            .performScrollTo().assertIsDisplayed()
        compose.runOnIdle { state.value = MiniState.Rebooting }
        compose.onNodeWithText("Waiting for your Mini to restart…").assertIsDisplayed()
    }

    private fun noCheck(state: MiniState) {
        compose.show(false) {
            HomeScreen(MiniSnapshot(state), onRefresh = {}, onConnect = {}, onDetails = {},
                onSetupChoice = {}, animateGuides = false, onHelp = {})
        }
        compose.onNodeWithText("Check again").assertDoesNotExist()
    }

    private fun retry(timedOut: Boolean) {
        var checks = 0
        compose.show(false) {
            HomeScreen(MiniSnapshot(MiniState.NotFound, probeTimedOut = timedOut),
                onRefresh = { checks++ }, onConnect = {}, onDetails = {}, onSetupChoice = {}, animateGuides = false)
        }
        compose.onNodeWithText("Check again").performScrollTo().performClick()
        assertEquals(1, checks)
    }
}
