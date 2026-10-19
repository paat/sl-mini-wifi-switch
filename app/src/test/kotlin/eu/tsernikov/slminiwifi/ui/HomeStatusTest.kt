package eu.tsernikov.slminiwifi.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import eu.tsernikov.slminiwifi.protocol.AuthKind
import eu.tsernikov.slminiwifi.protocol.BasicServiceSet
import eu.tsernikov.slminiwifi.protocol.Network
import eu.tsernikov.slminiwifi.protocol.Responses
import eu.tsernikov.slminiwifi.protocol.Wire
import eu.tsernikov.slminiwifi.protocol.WifiConfig
import eu.tsernikov.slminiwifi.state.MiniDetails
import eu.tsernikov.slminiwifi.state.MiniSnapshot
import eu.tsernikov.slminiwifi.state.MiniState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Fixed read-only snapshots: no transport and no real credentials. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "en-rUS-w360dp-h640dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class HomeStatusTest {
    @get:Rule val compose = createComposeRule()

    @Test fun configuredShowsQuickStatusWithoutOpeningDetails() {
        // Exercise the actual response boundary: the router's mask must never reach the UI.
        val password = Wire.Writer().string(1, "•••••").toByteArray()
        val band = Wire.Writer().string(1005, "Mini-Camp").varint(1011, 2)
            .bytes(2002, password).toByteArray()
        val network = Wire.Writer().bytes(1007, band).toByteArray()
        val config = Wire.Writer().varint(7, 1).bytes(1100, network).toByteArray()
        val response = Responses.parse(Wire.Writer().bytes(3009,
            Wire.Writer().bytes(1, config).toByteArray()).toByteArray())
        show(MiniSnapshot(MiniState.Configured, MiniDetails(config = response.wifiGetConfig)))
        compose.onNodeWithText("Wi-Fi status").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Wi-Fi").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("On").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Wi-Fi name").performScrollTo().assertIsDisplayed()
        compose.onAllNodesWithText("Mini-Camp").assertCountEquals(1)
        compose.onNodeWithText("Password").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(HIDDEN_PASSWORD).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("•••••", substring = true).assertDoesNotExist()
        compose.onNodeWithText("Show my Wi-Fi password").assertDoesNotExist()
        compose.onNodeWithText("2.4 GHz").assertDoesNotExist()
        compose.onNodeWithText("5 GHz").assertDoesNotExist()
    }

    @Test fun configuredSavedPasswordOpensExistingFlowOnlyAfterTap() {
        var opens = 0
        show(configured(), onShowCredentials = { opens++ })
        compose.onNodeWithText("Wi-Fi status").performScrollTo().assertIsDisplayed()
        assertEquals(0, opens)
        compose.onNodeWithText("Show my Wi-Fi password").performScrollTo().performClick()
        assertEquals(1, opens)
        compose.onNodeWithText("•••••", substring = true).assertDoesNotExist()
    }

    @Test fun configuredOpenNetworkSaysNoPassword() {
        show(configured(AuthKind.OPEN))
        compose.onNodeWithText("No password").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(HIDDEN_PASSWORD).assertDoesNotExist()
        compose.onNodeWithText("Show my Wi-Fi password").assertDoesNotExist()
    }

    @Test fun configuredUnknownAuthenticationNeverClaimsNoPassword() {
        show(configured(null))
        compose.onNodeWithText("Wi-Fi status").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("No password").assertDoesNotExist()
        compose.onNodeWithText("•••••", substring = true).assertDoesNotExist()
    }

    @Test fun configuredSummarisesAllBandsOff() {
        show(configured().let { it.copy(details = it.details.copy(config = it.details.config!!.copy(
            disable2Ghz = true, disable5Ghz = true))) })
        compose.onNodeWithText("Off").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("On").assertDoesNotExist()
        compose.onNodeWithText("2.4 GHz").assertDoesNotExist()
        compose.onNodeWithText("5 GHz").assertDoesNotExist()
    }

    @Test fun configuredShowsDifferentBandStates() {
        show(configured().let { it.copy(details = it.details.copy(config = it.details.config!!.copy(
            disable2Ghz = true))) })
        compose.onNodeWithText("2.4 GHz").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("5 GHz").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("On").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Off").performScrollTo().assertIsDisplayed()
    }

    @Test fun configuredMixedOpenAndSecuredBandsSaysPasswordUnavailable() {
        show(configuredBands(listOf(
            BasicServiceSet(ssid = "Mini-Camp", band = 2, authKind = AuthKind.OPEN),
            BasicServiceSet(ssid = "Mini-Camp", band = 5, authKind = AuthKind.WPA2_WPA3),
        )), onShowCredentials = {})
        compose.onNodeWithText("Not available").performScrollTo().assertIsDisplayed()
        compose.onAllNodesWithText("Not available").assertCountEquals(1)
        compose.onNodeWithText(HIDDEN_PASSWORD).assertDoesNotExist()
        compose.onNodeWithText("No password").assertDoesNotExist()
        compose.onNodeWithText("Show my Wi-Fi password").assertDoesNotExist()
    }

    @Test fun configuredUnknownBandUsesItsNumber() {
        show(configuredBands(listOf(
            BasicServiceSet(ssid = "Mini-Camp", band = 2, authKind = AuthKind.WPA2),
            BasicServiceSet(ssid = "Mini-Camp", band = 7, disable = true, authKind = AuthKind.WPA2),
        )))
        compose.onNodeWithText("2.4 GHz").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Band 7").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Wi-Fi").assertDoesNotExist()
        compose.onNodeWithText("On").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Off").performScrollTo().assertIsDisplayed()
    }

    @Test fun configuredDisabledServiceSetTurnsThatBandOff() {
        val bands = listOf(
            BasicServiceSet(ssid = "Mini-Camp", band = 2, disable = true, authKind = AuthKind.WPA2),
            BasicServiceSet(ssid = "Mini-Camp", band = 5, authKind = AuthKind.WPA2),
        )
        show(MiniSnapshot(MiniState.Configured, MiniDetails(config = WifiConfig(
            setupComplete = true, networks = listOf(Network(basicServiceSets = bands))))))
        compose.onNodeWithText("2.4 GHz").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Off").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("On").performScrollTo().assertIsDisplayed()
    }

    @Test fun configuredShowsEveryDistinctWifiName() {
        val bands = listOf(
            BasicServiceSet(ssid = "Mini-Camp", band = 2, authKind = AuthKind.WPA2),
            BasicServiceSet(ssid = "Mini-Camp", band = 5, authKind = AuthKind.WPA2),
            BasicServiceSet(ssid = "Mini-Guests", band = 5, authKind = AuthKind.OPEN),
        )
        show(MiniSnapshot(MiniState.Configured, MiniDetails(config = WifiConfig(
            setupComplete = true, networks = listOf(Network(basicServiceSets = bands))))))
        compose.onAllNodesWithText("Mini-Camp").assertCountEquals(1)
        compose.onAllNodesWithText("Mini-Guests").assertCountEquals(1)
        compose.onNodeWithText(HIDDEN_PASSWORD).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("No password").performScrollTo().assertIsDisplayed()
    }

    @Test fun configuredResetIsProminentAndNeverOffersSetup() = resetActions(configured())
    @Test fun bypassResetIsProminentAndNeverOffersSetup() = resetActions(MiniSnapshot(MiniState.Bypass))

    @Test fun bypassExplainsDirectConnectionInPlainWords() {
        show(MiniSnapshot(MiniState.Bypass))
        compose.onNodeWithText("Wi-Fi is off. Your phone reaches Starlink directly through the Mini.")
            .performScrollTo().assertIsDisplayed()
    }

    @Test fun unknownResetIsProminentAndNeverOffersSetup() = resetActions(MiniSnapshot(MiniState.Unknown))

    @Test fun unknownHasDistinctExplanation() {
        show(MiniSnapshot(MiniState.Unknown))
        compose.onNodeWithText("We can’t read this Mini’s settings").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Wi-Fi status").assertDoesNotExist()
        noSetupActions()
    }

    @Test fun configuredWithoutNetworksNeverInventsWifiStatus() {
        show(MiniSnapshot(MiniState.Configured, MiniDetails(config = WifiConfig(setupComplete = true))))
        compose.onNodeWithText("Wi-Fi status").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("On").assertDoesNotExist()
        compose.onNodeWithText("Off").assertDoesNotExist()
        compose.onNodeWithText("No password").assertDoesNotExist()
        compose.onAllNodesWithText("Not available").assertCountEquals(3)
    }

    @Test fun configuredReadableAtDoubleFontSize() = accessible(configured())
    @Test fun bypassReadableAtDoubleFontSize() = accessible(MiniSnapshot(MiniState.Bypass))
    @Test fun unknownReadableAtDoubleFontSize() = accessible(MiniSnapshot(MiniState.Unknown))

    private fun resetActions(snapshot: MiniSnapshot) {
        var resets = 0
        show(snapshot, onHelp = { resets++ })
        compose.onNodeWithText(RESET_NOTE).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("How to reset the Mini").performScrollTo()
            .assertIsDisplayed().assertHeightIsAtLeast(64.dp).performClick()
        assertEquals(1, resets)
        noSetupActions()
    }

    private fun noSetupActions() {
        listOf("Wi-Fi off", "Wi-Fi on with password", "Join STARLINK", "Start here").forEach {
            compose.onNodeWithText(it).assertDoesNotExist()
        }
    }

    private fun accessible(snapshot: MiniSnapshot) {
        show(snapshot, fontScale = 2f)
        compose.onNodeWithContentDescription(when (snapshot.state) {
            MiniState.Configured -> "Check mark: Mini already set up"
            MiniState.Bypass -> "Wi-Fi symbol crossed out"
            else -> "Magnifying glass: Mini settings could not be read"
        })
            .performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("How to reset the Mini").performScrollTo().assertHeightIsAtLeast(64.dp)
        val actions = compose.onAllNodes(hasClickAction())
        assertTrue(actions.fetchSemanticsNodes().isNotEmpty())
        repeat(actions.fetchSemanticsNodes().size) { index ->
            actions[index].performScrollTo().assertIsDisplayed()
                .assertHeightIsAtLeast(48.dp).assertWidthIsAtLeast(48.dp)
        }
        val layouts = compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsActions.GetTextLayoutResult),
            useUnmergedTree = true).fetchSemanticsNodes()
        assertTrue(layouts.isNotEmpty())
        layouts.forEach { node ->
            val results = mutableListOf<TextLayoutResult>()
            node.config[SemanticsActions.GetTextLayoutResult].action?.invoke(results)
            results.forEach(::assertTextFits)
        }
    }

    private fun show(snapshot: MiniSnapshot, fontScale: Float = 1f,
        onShowCredentials: (() -> Unit)? = null, onHelp: () -> Unit = {}) {
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, fontScale)) {
                MiniTheme {
                    HomeScreen(snapshot, onRefresh = {}, onConnect = {}, onDetails = {}, onSetupChoice = {},
                        animateGuides = false, onGuide = {}, onShowCredentials = onShowCredentials, onHelp = onHelp,
                        savedWifiName = if (onShowCredentials != null) "Mini-Camp" else null)
                }
            }
        }
    }

    private fun configured(auth: AuthKind? = AuthKind.WPA2): MiniSnapshot = configuredBands(listOf(
        BasicServiceSet(ssid = "Mini-Camp", band = 2, authKind = auth),
        BasicServiceSet(ssid = "Mini-Camp", band = 5, authKind = auth),
    ))

    private fun configuredBands(bands: List<BasicServiceSet>): MiniSnapshot = MiniSnapshot(
        MiniState.Configured, MiniDetails(config = WifiConfig(
            setupComplete = true, networks = listOf(Network(basicServiceSets = bands)))))

    companion object {
        private const val HIDDEN_PASSWORD = "The Mini keeps the password hidden. Check where you wrote it down, or reset the Mini to choose a new one."
        private const val RESET_NOTE = "To change this setup, find the reset symbol on the back of the Mini. Press until it clicks. Hold for about 3 seconds."
    }
}
