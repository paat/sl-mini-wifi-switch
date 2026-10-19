package eu.tsernikov.slminiwifi.ui

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import eu.tsernikov.slminiwifi.data.Profile
import eu.tsernikov.slminiwifi.protocol.AuthKind
import eu.tsernikov.slminiwifi.protocol.BasicServiceSet
import eu.tsernikov.slminiwifi.protocol.Network
import eu.tsernikov.slminiwifi.protocol.WifiConfig
import eu.tsernikov.slminiwifi.state.MiniDetails
import eu.tsernikov.slminiwifi.state.MiniSnapshot
import eu.tsernikov.slminiwifi.state.MiniState
import eu.tsernikov.slminiwifi.state.WifiOffError
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "en-rUS-w411dp-h891dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class RobustnessScreensTest {
    @get:Rule val compose = createComposeRule()

    private val savedDetails = MiniDetails(config = WifiConfig(setupComplete = true,
        networks = listOf(Network(basicServiceSets = listOf(
            BasicServiceSet(ssid = "Mini-Camp", authKind = AuthKind.WPA2_WPA3),
        )))))

    @Test fun savedPasswordEntryAvailableExceptOnSetupPendingHome() {
        val model = eu.tsernikov.slminiwifi.SetupViewModel(org.robolectric.RuntimeEnvironment.getApplication())
        model.savedProfile = Profile("Mini-Camp", "Camp7River9Sunny2")
        var opens = 0
        compose.setContent {
            MiniTheme {
                HomeScreen(model.snapshot, onRefresh = {}, onConnect = {}, onDetails = {},
                    onSetupChoice = {}, animateGuides = false,
                    onShowCredentials = if (model.canShowHomeCredentials) ({ opens++ }) else null,
                    savedWifiName = model.savedProfile?.name)
            }
        }
        MiniState.entries.forEach { current ->
            compose.runOnIdle { model.snapshot = MiniSnapshot(current, savedDetails) }
            if (current == MiniState.SetupPending) {
                compose.onNodeWithText("Show my Wi-Fi password").assertDoesNotExist()
            } else {
                compose.onNodeWithText("Show my Wi-Fi password").performScrollTo().assertIsDisplayed().performClick()
            }
        }
        assertEquals(MiniState.entries.size - 1, opens)
        model.networks.close()
    }

    @Test fun savedPasswordHomeEntryReachesPasswordAndQrInEveryPostSetupState() {
        val model = eu.tsernikov.slminiwifi.SetupViewModel(org.robolectric.RuntimeEnvironment.getApplication())
        val saved = Profile("Mini-Camp", "Camp7River9Sunny2")
        model.savedProfile = saved
        compose.setContent {
            MiniTheme {
                if (model.screen == eu.tsernikov.slminiwifi.SetupViewModel.Screen.Home) {
                    HomeScreen(model.snapshot, onRefresh = {}, onConnect = {}, onDetails = {},
                        onSetupChoice = {}, animateGuides = false,
                        onShowCredentials = if (model.canShowHomeCredentials) model::showSavedPassword else null,
                        savedWifiName = model.savedProfile?.name)
                } else {
                    WifiOnResultScreen(model.profile, {}, null, model::returnHome, verified = model.wifiVerified)
                }
            }
        }
        try {
            for (state in listOf(MiniState.Configured, MiniState.Rebooting, MiniState.NotFound)) {
                compose.runOnIdle { model.snapshot = MiniSnapshot(state, savedDetails) }
                compose.onNodeWithText("Show my Wi-Fi password").performScrollTo().performClick()
                compose.onNodeWithText(saved.password).performScrollTo().assertIsDisplayed()
                compose.onNodeWithContentDescription("Wi-Fi code. Scan with another phone to join this Wi-Fi.")
                    .performScrollTo().assertIsDisplayed()
                compose.onNodeWithText("Done").performScrollTo().performClick()
            }
        } finally { model.networks.close() }
    }

    @Test fun homeDoesNotOfferPasswordWithoutSavedProfile() {
        compose.setContent {
            MiniTheme {
                HomeScreen(MiniSnapshot(MiniState.Configured), onRefresh = {}, onConnect = {},
                    onDetails = {}, onSetupChoice = {}, animateGuides = false)
            }
        }
        compose.onNodeWithText("Show my Wi-Fi password").assertDoesNotExist()
    }

    @Test fun unicodeNameLimitHasPlainRecoveryAndCannotContinue() {
        val name = mutableStateOf("é".repeat(17))
        compose.setContent {
            MiniTheme {
                WifiOnSetupScreen(Profile(name.value, "Camp7River9Sunny2"), false, false,
                    {}, {}, {}, {}, {}, {}, {})
            }
        }
        compose.onNodeWithText("Shorten this name, or use English letters and numbers.")
            .performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("bytes", substring = true, ignoreCase = true).assertDoesNotExist()
        compose.onNodeWithText("Review this choice").performScrollTo().assertIsNotEnabled()
        compose.runOnIdle { name.value = "é".repeat(16) }
        compose.onNodeWithText("Review this choice").performScrollTo().assertIsEnabled()
        compose.onNodeWithText("Shorten this name, or use English letters and numbers.").assertDoesNotExist()
    }

    @Test fun emptyNameExplainsHowToContinue() {
        compose.setContent {
            MiniTheme { WifiOnSetupScreen(Profile("", "Camp7River9Sunny2"), false, false,
                {}, {}, {}, {}, {}, {}, {}) }
        }
        compose.onNodeWithText("Enter a Wi-Fi name.").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Review this choice").performScrollTo().assertIsNotEnabled()
    }

    @Test fun wifiOffConfirmationWaitsWithoutClaimingWriteFailure() {
        compose.setContent {
            MiniTheme { WifiOffConfirmScreen(true, {}, {}, animateGuides = false, canConfirm = false) }
        }
        compose.onNodeWithText("No change has been sent.", substring = true).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Turn Wi-Fi off").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("Cannot reach the Mini").assertDoesNotExist()
        compose.onNodeWithText("Cancel").performScrollTo().assertIsEnabled()
    }

    @Test fun wifiOnConfirmationWaitsWithoutClaimingWriteFailure() {
        compose.setContent {
            MiniTheme { WifiOnConfirmScreen("Mini-Camp", {}, {}, canConfirm = false) }
        }
        compose.onNodeWithText("No change has been sent.", substring = true).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Turn Wi-Fi on").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("Cannot reach the Mini").assertDoesNotExist()
        compose.onNodeWithText("Cancel").performScrollTo().assertIsEnabled()
    }

    @Test fun reconnectOfferNeedsExplicitTapAndCanBeOfferedAgain() {
        var reconnects = 0
        compose.setContent { MiniTheme { WifiOnProgressScreen(onReconnect = { reconnects++ }) } }
        assertEquals(0, reconnects)
        repeat(2) {
            compose.onNodeWithText("Try connecting again").performScrollTo().assertIsDisplayed().performClick()
        }
        assertEquals(2, reconnects)
    }

    @Test fun unreachableWifiErrorOffersReadOnlyReconnect() {
        var reconnects = 0
        compose.setContent {
            MiniTheme { WifiOffErrorScreen(WifiOffError.CantReach, {}, animateGuides = false,
                onReconnect = { reconnects++ }) }
        }
        compose.onNodeWithText("This only checks the Mini; it does not send your choice again.", substring = true)
            .performScrollTo().assertIsDisplayed()
        assertEquals(0, reconnects)
        compose.onNodeWithText("Try connecting again").performScrollTo().performClick()
        assertEquals(1, reconnects)
    }

    @Test fun detailsIdentifiesReadingsThatMayHaveChanged() {
        compose.setContent { MiniTheme { DetailsScreen(MiniDetails(), {}) } }
        compose.onNodeWithText("Last readings from the Mini. Some details may have changed.").assertIsDisplayed()
    }

    @Test fun savedProfileResultHasPasswordAndJoinWithoutEmptyDetails() {
        compose.setContent {
            MiniTheme { WifiOnResultScreen(Profile("Mini-Camp", "Camp7River9Sunny2"), {}, null, {}, verified = false) }
        }
        compose.onNodeWithText("Camp7River9Sunny2").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Join on this phone").performScrollTo().assertIsEnabled()
        compose.onNodeWithText("Details").assertDoesNotExist()
    }

    @Test fun wifiOffResultOmitsDetailsWhenSnapshotIsUnavailable() {
        compose.setContent {
            MiniTheme { WifiOffResultScreen(true, null, {}, animateGuides = false) }
        }
        compose.onNodeWithText("Details").assertDoesNotExist()
        compose.onNodeWithText("Done").performScrollTo().assertIsEnabled()
    }

    @Test fun likelyWifiOffResultDoesNotClaimCertainty() {
        compose.setContent {
            MiniTheme { WifiOffResultScreen(true, {}, {}, animateGuides = false) }
        }
        compose.onNodeWithText("Wi-Fi is likely off").assertIsDisplayed()
        compose.onNodeWithText("Wi-Fi is off").assertDoesNotExist()
        compose.onNodeWithText("The phone lost its connection", substring = true).assertDoesNotExist()
        compose.onNodeWithText("We could not fully check the change.", substring = true).assertIsDisplayed()
    }
}
