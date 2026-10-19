package eu.tsernikov.slminiwifi.ui

import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.github.takahirom.roborazzi.captureRoboImage
import eu.tsernikov.slminiwifi.state.WifiOffError
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Rendering and callback tests only. These screens never instantiate a network transport. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "en-rUS-w411dp-h891dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class WifiOffScreenshotsTest {
    @get:Rule val compose = createComposeRule()

    @Test fun confirmEthernetLight() = confirmation(isWifi = false, dark = false)
    @Test fun confirmEthernetDark() = confirmation(isWifi = false, dark = true)
    @Test fun confirmWifiLight() = confirmation(isWifi = true, dark = false)
    @Test fun confirmWifiDark() = confirmation(isWifi = true, dark = true)
    @Test fun progressLight() = progress(false)
    @Test fun progressDark() = progress(true)
    @Test fun resultLight() = result(likely = false, dark = false)
    @Test fun resultDark() = result(likely = false, dark = true)
    @Test fun likelyResultLight() = result(likely = true, dark = false)
    @Test fun likelyResultDark() = result(likely = true, dark = true)
    @Test fun alreadySetUpLight() = error(WifiOffError.AlreadySetUp, false)
    @Test fun alreadySetUpDark() = error(WifiOffError.AlreadySetUp, true)
    @Test fun refusedLight() = error(WifiOffError.Refused, false)
    @Test fun refusedDark() = error(WifiOffError.Refused, true)
    @Test fun cantReachLight() = error(WifiOffError.CantReach, false)
    @Test fun cantReachDark() = error(WifiOffError.CantReach, true)
    @Test fun resetGuideLight() = reset(false)
    @Test fun resetGuideDark() = reset(true)

    @Test fun confirmationAtTwoHundredPercentFont() = confirmation(isWifi = true, dark = false, fontScale = 2f)
    @Test fun resultAtTwoHundredPercentFont() = result(likely = true, dark = false, fontScale = 2f)
    @Test fun resetAtTwoHundredPercentFont() = reset(dark = false, fontScale = 2f)

    private fun confirmation(isWifi: Boolean, dark: Boolean, fontScale: Float = 1f) {
        var confirms = 0
        var cancels = 0
        compose.show(dark, fontScale) {
            WifiOffConfirmScreen(isWifi, { confirms++ }, { cancels++ }, animateGuides = false)
        }
        val name = "confirm_${if (isWifi) "wifi" else "ethernet"}_${suffix(dark, fontScale)}"
        capture(name)
        assertEquals(0, confirms)
        assertEquals(0, cancels)
        compose.onNodeWithText("Cancel").performScrollTo().assertIsDisplayed().performClick()
        assertEquals(0, confirms)
        assertEquals(1, cancels)
        capture("${name}_actions")
        compose.onNodeWithText("Turn Wi-Fi off").performScrollTo().assertIsDisplayed().performClick()
        assertEquals(1, confirms)
    }

    private fun progress(dark: Boolean) {
        compose.show(dark) { WifiOffProgressScreen() }
        compose.mainClock.advanceTimeBy(400)
        compose.onNodeWithText("This usually takes less than 1 minute.").assertIsDisplayed()
        capture("progress_${suffix(dark)}")
    }

    private fun result(likely: Boolean, dark: Boolean, fontScale: Float = 1f) {
        var done = 0
        var details = 0
        compose.show(dark, fontScale) {
            WifiOffResultScreen(likely, { details++ }, { done++ }, animateGuides = false)
        }
        val name = "result_${if (likely) "likely_" else ""}${suffix(dark, fontScale)}"
        capture(name)
        compose.onNodeWithText("Details").performScrollTo().assertIsDisplayed().performClick()
        assertEquals(1, details)
        capture("${name}_actions")
        compose.onNodeWithText("Done").performScrollTo().assertIsDisplayed().performClick()
        assertEquals(1, done)
    }

    private fun error(error: WifiOffError, dark: Boolean) {
        var next = 0
        compose.show(dark) { WifiOffErrorScreen(error, { next++ }, animateGuides = false) }
        capture("error_${when (error) {
            WifiOffError.AlreadySetUp -> "already_set_up"
            WifiOffError.Refused -> "refused"
            WifiOffError.CantReach -> "cant_reach"
        }}_${suffix(dark)}")
        compose.onNodeWithText(if (error == WifiOffError.AlreadySetUp) "Show reset steps" else "Reconnect to Mini")
            .performScrollTo().assertIsDisplayed().assertHasClickAction().performClick()
        assertEquals(1, next)
    }

    private fun reset(dark: Boolean, fontScale: Float = 1f) {
        var checks = 0
        compose.show(dark, fontScale) { ResetGuideScreen({ checks++ }, animateGuides = false) }
        val name = "reset_guide_${suffix(dark, fontScale)}"
        capture(name)
        compose.onNodeWithText("Check again").performScrollTo().assertIsDisplayed().performClick()
        assertEquals(1, checks)
        capture("${name}_actions")
    }

    private fun capture(name: String) {
        val directory = File(requireNotNull(System.getProperty("m2.screenshots.dir")))
        compose.onRoot().captureRoboImage(File(directory, "$name.png").absolutePath)
    }

    private fun suffix(dark: Boolean, fontScale: Float = 1f): String =
        (if (dark) "dark" else "light") + if (fontScale == 2f) "_font_200" else ""
}
