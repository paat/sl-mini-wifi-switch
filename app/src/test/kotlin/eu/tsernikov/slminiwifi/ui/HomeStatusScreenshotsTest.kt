package eu.tsernikov.slminiwifi.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performScrollTo
import com.github.takahirom.roborazzi.captureRoboImage
import eu.tsernikov.slminiwifi.protocol.AuthKind
import eu.tsernikov.slminiwifi.protocol.BasicServiceSet
import eu.tsernikov.slminiwifi.protocol.Network
import eu.tsernikov.slminiwifi.protocol.WifiConfig
import eu.tsernikov.slminiwifi.state.MiniDetails
import eu.tsernikov.slminiwifi.state.MiniSnapshot
import eu.tsernikov.slminiwifi.state.MiniState
import java.io.File
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Offline home snapshots with a fictional Wi-Fi name. Saved passwords remain behind their existing action. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "en-rUS-w411dp-h891dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class HomeStatusScreenshotsTest {
    @get:Rule val compose = createComposeRule()

    @Test fun configuredHiddenLight() = home(MiniState.Configured, false, false)
    @Test fun configuredHiddenDark() = home(MiniState.Configured, false, true)
    @Test fun configuredHiddenFont200Light() = home(MiniState.Configured, false, false, 2f)
    @Test fun configuredHiddenFont200Dark() = home(MiniState.Configured, false, true, 2f)
    @Test fun configuredSavedLight() = home(MiniState.Configured, true, false)
    @Test fun configuredSavedDark() = home(MiniState.Configured, true, true)
    @Test fun configuredSavedFont200Light() = home(MiniState.Configured, true, false, 2f)
    @Test fun configuredSavedFont200Dark() = home(MiniState.Configured, true, true, 2f)
    @Test fun bypassLight() = home(MiniState.Bypass, false, false)
    @Test fun bypassDark() = home(MiniState.Bypass, false, true)
    @Test fun bypassFont200Light() = home(MiniState.Bypass, false, false, 2f)
    @Test fun bypassFont200Dark() = home(MiniState.Bypass, false, true, 2f)
    @Test fun unknownLight() = home(MiniState.Unknown, false, false)
    @Test fun unknownDark() = home(MiniState.Unknown, false, true)
    @Test fun unknownFont200Light() = home(MiniState.Unknown, false, false, 2f)
    @Test fun unknownFont200Dark() = home(MiniState.Unknown, false, true, 2f)

    private fun home(state: MiniState, saved: Boolean, dark: Boolean, fontScale: Float = 1f) {
        val details = if (state == MiniState.Configured) MiniDetails(config = WifiConfig(setupComplete = true,
            networks = listOf(Network(basicServiceSets = listOf(
                BasicServiceSet(ssid = "Mini-Camp", band = 2, authKind = AuthKind.WPA2),
                BasicServiceSet(ssid = "Mini-Camp", band = 5, authKind = AuthKind.WPA2),
            ))))) else MiniDetails()
        compose.show(dark, fontScale) {
            HomeScreen(MiniSnapshot(state, details), onRefresh = {}, onConnect = {}, onDetails = {},
                onSetupChoice = {}, animateGuides = false, onGuide = {}, onHelp = {},
                onShowCredentials = if (saved) ({}) else null,
                savedWifiName = if (saved) "Mini-Camp" else null)
        }
        val name = state.name.lowercase() + (if (state == MiniState.Configured)
            if (saved) "_saved" else "_hidden" else "") +
            (if (dark) "_dark" else "_light") + (if (fontScale == 2f) "_font_200" else "")
        capture(name)
        if (state == MiniState.Configured) {
            compose.onNodeWithText(if (saved) "Show my Wi-Fi password" else
                "The Mini keeps the password hidden. Check where you wrote it down, or reset the Mini to choose a new one.")
                .performScrollTo().assertIsDisplayed()
            capture("${name}_password")
        }
        compose.onNodeWithText("How to reset the Mini").performScrollTo().assertIsDisplayed()
        capture("${name}_actions")
    }

    private fun capture(name: String) {
        val directory = File(requireNotNull(System.getProperty("m5.screenshots.dir")))
        compose.onRoot().captureRoboImage(File(directory, "$name.png").absolutePath)
    }
}
