package eu.tsernikov.slminiwifi.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import com.github.takahirom.roborazzi.captureRoboImage
import eu.tsernikov.slminiwifi.data.Profile
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Only the disconnected setup presentations changed; credentials are fictional. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "en-rUS-w411dp-h891dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SetupReconnectScreensTest {
    @get:Rule val compose = createComposeRule()

    @Test fun editingReconnectLight() = reconnect("editing", false)
    @Test fun editingReconnectDark() = reconnect("editing", true)
    @Test fun editingReconnectFont200Light() = reconnect("editing", false, 2f)
    @Test fun editingReconnectFont200Dark() = reconnect("editing", true, 2f)
    @Test fun confirmReconnectLight() = reconnect("confirm", false)
    @Test fun confirmReconnectDark() = reconnect("confirm", true)
    @Test fun confirmReconnectFont200Light() = reconnect("confirm", false, 2f)
    @Test fun confirmReconnectFont200Dark() = reconnect("confirm", true, 2f)
    @Test fun wifiOffReconnectLight() = reconnect("wifi_off", false)
    @Test fun wifiOffReconnectDark() = reconnect("wifi_off", true)

    private fun reconnect(screen: String, dark: Boolean, fontScale: Float = 1f) {
        val profile = Profile("Mini-Camp", "Camp7River9Sunny2")
        var reconnects = 0
        var writes = 0
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, fontScale)) {
                MiniTheme(darkTheme = dark) {
                    when (screen) {
                        "editing" -> WifiOnSetupScreen(profile, true, false, {}, {}, {}, {}, {}, {}, {},
                            onReconnect = { reconnects++ }, canConfirm = false)
                        "confirm" -> WifiOnConfirmScreen(profile.name, { writes++ }, {}, canConfirm = false,
                            onReconnect = { reconnects++ })
                        else -> WifiOffConfirmScreen(true, { writes++ }, {}, animateGuides = false,
                            canConfirm = false, onReconnect = { reconnects++ })
                    }
                }
            }
        }
        assertEquals(0, reconnects)
        compose.onNodeWithText("Checking the connection before you confirm.", substring = true).assertDoesNotExist()
        compose.onNodeWithText("Reconnect to the Mini").performScrollTo().assertIsDisplayed()
        val suffix = "${if (dark) "dark" else "light"}${if (fontScale == 2f) "_font_200" else ""}"
        val directory = File(requireNotNull(System.getProperty("m3.screenshots.dir")))
        compose.onRoot().captureRoboImage(File(directory, "setup_reconnect_${screen}_$suffix.png").absolutePath)
        compose.onNodeWithText("Reconnect to the Mini").performClick()
        assertEquals(1, reconnects)
        if (screen == "editing") {
            compose.onNodeWithText("Show password").performScrollTo().performClick()
            compose.onNodeWithText(profile.password).assertIsDisplayed()
        } else {
            compose.onNodeWithText(if (screen == "confirm") "Turn Wi-Fi on" else "Turn Wi-Fi off")
                .performScrollTo().assertIsNotEnabled()
        }
        assertEquals(0, writes)
    }
}
