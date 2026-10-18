package eu.tsernikov.slminiwifi.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import com.github.takahirom.roborazzi.captureRoboImage
import eu.tsernikov.slminiwifi.protocol.AuthKind
import eu.tsernikov.slminiwifi.protocol.BasicServiceSet
import eu.tsernikov.slminiwifi.protocol.DeviceInfo
import eu.tsernikov.slminiwifi.protocol.Network
import eu.tsernikov.slminiwifi.protocol.WifiConfig
import eu.tsernikov.slminiwifi.protocol.WifiGetStatusResponse
import eu.tsernikov.slminiwifi.state.MiniDetails
import eu.tsernikov.slminiwifi.state.MiniSnapshot
import eu.tsernikov.slminiwifi.state.MiniState
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Regular runs exercise rendering/actions; only recordRoborazziDebug writes PNGs. No Mini is contacted. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "en-rUS-w411dp-h891dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ScreenshotsTest {
    @get:Rule val compose = createComposeRule()

    @Test fun setupPendingLight() = home(MiniState.SetupPending, false)
    @Test fun setupPendingDark() = home(MiniState.SetupPending, true)
    @Test fun configuredLight() = home(MiniState.Configured, false)
    @Test fun configuredDark() = home(MiniState.Configured, true)
    @Test fun wifiOffLight() = home(MiniState.Bypass, false)
    @Test fun wifiOffDark() = home(MiniState.Bypass, true)
    @Test fun rebootingLight() = home(MiniState.Rebooting, false)
    @Test fun rebootingDark() = home(MiniState.Rebooting, true)
    @Test fun notFoundLight() = home(MiniState.NotFound, false)
    @Test fun notFoundDark() = home(MiniState.NotFound, true)
    @Test fun detailsLight() = details(false)
    @Test fun detailsDark() = details(true)
    @Test fun wifiOnPreviewLight() = preview(false)
    @Test fun wifiOnPreviewDark() = preview(true)

    @Test fun setupPendingLargeFont() {
        var chosen: Boolean? = null
        show(dark = false, fontScale = 2f) {
            HomeScreen(MiniSnapshot(MiniState.SetupPending), onRefresh = {}, onConnect = {},
                onDetails = {}, onSetupChoice = { chosen = it }, animateGuides = false)
        }
        capture("home_setup_pending_font_200")
        compose.onNodeWithText("Wi-Fi off").performScrollTo().assertIsDisplayed().performClick()
        assertEquals(true, chosen)
        compose.onNodeWithText("Wi-Fi on with password").performScrollTo().assertIsDisplayed().performClick()
        assertEquals(false, chosen)
    }

    private fun home(state: MiniState, dark: Boolean) {
        var connects = 0
        show(dark) {
            HomeScreen(MiniSnapshot(state, sampleDetails), onRefresh = {}, onConnect = { connects++ },
                onDetails = {}, onSetupChoice = {}, animateGuides = false)
        }
        if (state == MiniState.Rebooting) compose.mainClock.advanceTimeBy(400)
        val name = "home_${when (state) {
            MiniState.SetupPending -> "setup_pending"
            MiniState.Configured -> "configured"
            MiniState.Bypass -> "wifi_off"
            MiniState.Rebooting -> "rebooting"
            MiniState.NotFound -> "not_found"
        }}_${theme(dark)}"
        capture(name)
        val nextStep = when (state) {
            MiniState.SetupPending -> "Wi-Fi off"
            MiniState.Configured, MiniState.Bypass -> "Details"
            MiniState.Rebooting, MiniState.NotFound -> "Check again"
        }
        compose.onNodeWithText(nextStep).performScrollTo().assertIsDisplayed().assertHasClickAction()
        if (state == MiniState.NotFound) {
            compose.onNodeWithText("Join STARLINK").performScrollTo().assertIsDisplayed().performClick()
            assertEquals(1, connects)
            capture("${name}_wifi_guide")
        }
    }

    private fun details(dark: Boolean) {
        var backs = 0
        show(dark) { DetailsScreen(sampleDetails) { backs++ } }
        capture("details_${theme(dark)}")
        compose.onNodeWithText("2025.06.23.mr55202").assertIsDisplayed()
        compose.onNodeWithText("Wi-Fi bands").performScrollTo().assertIsDisplayed()
        compose.onAllNodesWithText("Back")[1].performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("5 GHz").assertIsDisplayed()
        capture("details_${theme(dark)}_bands")
        compose.onAllNodesWithText("Back")[1].performClick()
        assertEquals(1, backs)
    }

    private fun preview(dark: Boolean) {
        var backs = 0
        show(dark) { ComingSoonScreen() { backs++ } }
        compose.onNodeWithText("This step comes in the next version. Your Mini is ready and waiting.").assertIsDisplayed()
        capture("preview_wifi_on_${theme(dark)}")
        compose.onNodeWithText("Back").performClick()
        assertEquals(1, backs)
    }

    private fun show(dark: Boolean, fontScale: Float = 1f, content: @Composable () -> Unit) {
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, fontScale)) {
                MiniTheme(darkTheme = dark, content = content)
            }
        }
    }

    private fun capture(name: String) {
        // Roborazzi's default mode does not compare or record, keeping CI independent of fonts.
        val directory = File(requireNotNull(System.getProperty("m1.screenshots.dir")))
        compose.onRoot().captureRoboImage(File(directory, "$name.png").absolutePath)
    }

    private fun theme(dark: Boolean) = if (dark) "dark" else "light"

    private val sampleDetails = MiniDetails(
        routerInfo = DeviceInfo(hardwareVersion = "v4", softwareVersion = "2025.06.23.mr55202"),
        dishInfo = DeviceInfo(hardwareVersion = "mini1_prod1", softwareVersion = "2026.08.31.cr85832"),
        config = WifiConfig(setupComplete = true, networks = listOf(Network(
            ipv4 = "192.168.1.1/24",
            basicServiceSets = listOf(
                BasicServiceSet(ssid = "Field Mini", band = 2, authKind = AuthKind.WPA2_WPA3),
                BasicServiceSet(ssid = "Field Mini", band = 5, authKind = AuthKind.WPA2_WPA3),
            ),
        ))),
        status = WifiGetStatusResponse(ipv4WanAddress = "100.76.233.16"),
    )
}
