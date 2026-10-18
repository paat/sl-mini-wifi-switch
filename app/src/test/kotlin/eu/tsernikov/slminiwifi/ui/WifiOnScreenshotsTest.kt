package eu.tsernikov.slminiwifi.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import com.github.takahirom.roborazzi.captureRoboImage
import eu.tsernikov.slminiwifi.data.Profile
import eu.tsernikov.slminiwifi.state.WifiOffError
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Fixed fictional credentials; rendering and callback tests cannot contact a Mini. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "en-rUS-w411dp-h891dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class WifiOnScreenshotsTest {
    @get:Rule val compose = createComposeRule()
    private val profile = Profile("Mini-Camp", "Camp7River9Sunny2")

    @Test fun setupGeneratedLight() = setup(false, false, false)
    @Test fun setupGeneratedDark() = setup(false, false, true)
    @Test fun setupGeneratedSavedLight() = setup(false, true, false)
    @Test fun setupGeneratedSavedDark() = setup(false, true, true)
    @Test fun setupOwnLight() = setup(true, false, false)
    @Test fun setupOwnDark() = setup(true, false, true)
    @Test fun setupOwnSavedLight() = setup(true, true, false)
    @Test fun setupOwnSavedDark() = setup(true, true, true)
    @Test fun setupGeneratedFont200Light() = setup(false, true, false, 2f)
    @Test fun setupGeneratedFont200Dark() = setup(false, true, true, 2f)
    @Test fun setupOwnFont200Light() = setup(true, true, false, 2f)
    @Test fun setupOwnFont200Dark() = setup(true, true, true, 2f)
    @Test fun confirmLight() = confirm(false)
    @Test fun confirmDark() = confirm(true)
    @Test fun progressLight() = progress(false)
    @Test fun progressDark() = progress(true)
    @Test fun reconnectProgressLight() = reconnect(false, false)
    @Test fun reconnectProgressDark() = reconnect(true, false)
    @Test fun reconnectErrorLight() = reconnect(false, true)
    @Test fun reconnectErrorDark() = reconnect(true, true)
    @Test fun resultLight() = result(false)
    @Test fun resultDark() = result(true)
    @Test fun unverifiedResultLight() = result(false, verified = false)
    @Test fun unverifiedResultDark() = result(true, verified = false)
    @Test fun autoConnectLight() = autoConnect(false, false)
    @Test fun autoConnectDark() = autoConnect(false, true)
    @Test fun autoConnectApi29Light() = autoConnect(true, false)
    @Test fun autoConnectApi29Dark() = autoConnect(true, true)

    @Test fun invalidPasswordCannotContinue() {
        var continues = 0
        show(false) {
            WifiOnSetupScreen(Profile("Mini-Camp", "short"), true, false,
                {}, {}, {}, {}, {}, { continues++ }, {})
        }
        compose.onNodeWithText("Review this choice").performScrollTo().assertIsNotEnabled()
        assertEquals(0, continues)
    }

    private fun setup(own: Boolean, saved: Boolean, dark: Boolean, fontScale: Float = 1f) {
        var reviews = 0
        var choices = 0
        var reuses = 0
        show(dark, fontScale) {
            WifiOnSetupScreen(profile, own, saved, {}, {}, { choices++ }, { choices++ },
                { reuses++ }, { reviews++ }, {})
        }
        val name = "setup_${if (own) "own" else "generated"}_${if (saved) "saved" else "new"}_${suffix(dark, fontScale)}"
        capture(name)
        assertEquals(0, reviews)
        if (saved) {
            compose.onNodeWithText("Use the same Wi-Fi as last time").performScrollTo().performClick()
            assertEquals(1, reuses)
        }
        compose.onNodeWithText("Show password").performScrollTo().performClick()
        compose.onNodeWithText(profile.password).assertIsDisplayed()
        capture("${name}_password")
        compose.onNodeWithText("Hide password").performScrollTo().performClick()
        compose.onNodeWithText(if (own) "Make a password for me" else "Use my own password")
            .performScrollTo().performClick()
        assertEquals(1, choices)
        compose.onNodeWithText("Review this choice").performScrollTo().assertIsDisplayed().performClick()
        assertEquals(1, reviews)
        capture("${name}_actions")
    }

    private fun confirm(dark: Boolean) {
        var confirms = 0
        var cancels = 0
        show(dark) { WifiOnConfirmScreen(profile.name, { confirms++ }, { cancels++ }) }
        capture("confirm_${suffix(dark)}")
        assertEquals(0, confirms)
        compose.onNodeWithText("Cancel").performScrollTo().performClick()
        assertEquals(1, cancels)
        assertEquals(0, confirms)
        capture("confirm_${suffix(dark)}_actions")
        compose.onNodeWithText("Turn Wi-Fi on").performScrollTo().assertIsDisplayed().performClick()
        assertEquals(1, confirms)
    }

    private fun progress(dark: Boolean) {
        show(dark) { WifiOnProgressScreen() }
        compose.mainClock.advanceTimeBy(400)
        compose.onNodeWithText("Getting your Wi-Fi ready").assertIsDisplayed()
        capture("progress_${suffix(dark)}")
    }

    private fun reconnect(dark: Boolean, error: Boolean) {
        var reconnects = 0
        show(dark) {
            if (error) WifiOffErrorScreen(WifiOffError.CantReach, {}, animateGuides = false,
                unreachableBody = eu.tsernikov.slminiwifi.R.string.wifi_on_unreachable_body,
                onReconnect = { reconnects++ })
            else WifiOnProgressScreen(onReconnect = { reconnects++ })
        }
        compose.onNodeWithText("Try connecting again").performScrollTo().assertIsDisplayed()
        capture("reconnect_${if (error) "error" else "progress"}_${suffix(dark)}")
        assertEquals(0, reconnects)
        compose.onNodeWithText("Try connecting again").performClick()
        assertEquals(1, reconnects)
    }

    private fun result(dark: Boolean, verified: Boolean = true) {
        var connects = 0
        var details = 0
        var done = 0
        show(dark) { WifiOnResultScreen(profile, { connects++ }, { details++ }, { done++ }, verified = verified) }
        val name = if (verified) "result" else "unverified_result"
        capture("${name}_${suffix(dark)}")
        compose.onNodeWithText(profile.password).performScrollTo().assertIsDisplayed()
        compose.onNodeWithContentDescription("Wi-Fi code. Scan with another phone to join this Wi-Fi.")
            .performScrollTo().assertIsDisplayed()
        capture("${name}_${suffix(dark)}_qr")
        compose.onNodeWithText("Join on this phone").performScrollTo().performClick()
        assertEquals(1, connects)
        compose.onNodeWithText("Details").performScrollTo().performClick()
        assertEquals(1, details)
        compose.onNodeWithText("Done").performScrollTo().performClick()
        assertEquals(1, done)
        capture("${name}_${suffix(dark)}_actions")
    }

    private fun autoConnect(api29: Boolean, dark: Boolean) {
        var continues = 0
        var backs = 0
        show(dark) { WifiOnAutoConnectScreen(api29, { continues++ }, { backs++ }) }
        capture("auto_connect_${if (api29) "api29" else "save"}_${suffix(dark)}")
        assertEquals(0, continues)
        compose.onNodeWithText("Back").performScrollTo().performClick()
        assertEquals(1, backs)
        compose.onNodeWithText("Continue to Android").performScrollTo().performClick()
        assertEquals(1, continues)
    }

    private fun show(dark: Boolean, fontScale: Float = 1f, content: @Composable () -> Unit) {
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, fontScale)) {
                MiniTheme(darkTheme = dark, content = content)
            }
        }
    }

    private fun capture(name: String) {
        val directory = File(requireNotNull(System.getProperty("m3.screenshots.dir")))
        compose.onRoot().captureRoboImage(File(directory, "$name.png").absolutePath)
    }

    private fun suffix(dark: Boolean, fontScale: Float = 1f): String =
        (if (dark) "dark" else "light") + if (fontScale == 2f) "_font_200" else ""
}
