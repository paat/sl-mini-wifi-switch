package eu.tsernikov.slminiwifi.ui

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import eu.tsernikov.slminiwifi.R
import eu.tsernikov.slminiwifi.data.Profile
import eu.tsernikov.slminiwifi.protocol.AuthKind
import eu.tsernikov.slminiwifi.protocol.BasicServiceSet
import eu.tsernikov.slminiwifi.protocol.Network
import eu.tsernikov.slminiwifi.protocol.WifiConfig
import eu.tsernikov.slminiwifi.state.MiniDetails
import eu.tsernikov.slminiwifi.state.MiniSnapshot
import eu.tsernikov.slminiwifi.state.MiniState
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Estonian resources on a phone, with offline fixtures and the shared English screenshot host. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "et-w411dp-h891dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class EstonianScreenshotsTest {
    @get:Rule val compose = createComposeRule()
    private val profile = Profile("Mini-Matk", "Matka7Mets9")

    @Test fun readyLight() = home(MiniState.SetupPending, 1f)
    @Test fun readyFont200Light() = home(MiniState.SetupPending, 2f)
    @Test fun configuredLight() = home(MiniState.Configured, 1f)
    @Test fun configuredFont200Light() = home(MiniState.Configured, 2f)
    @Test fun wifiOffLight() = home(MiniState.Bypass, 1f)
    @Test fun wifiOffFont200Light() = home(MiniState.Bypass, 2f)
    @Test fun notFoundLight() = home(MiniState.NotFound, 1f)
    @Test fun notFoundFont200Light() = home(MiniState.NotFound, 2f)
    @Test fun resetLight() = reset(1f)
    @Test fun resetFont200Light() = reset(2f)
    @Test fun passwordLight() = password(1f)
    @Test fun passwordFont200Light() = password(2f)
    @Test fun confirmLight() = confirm(1f)
    @Test fun confirmFont200Light() = confirm(2f)

    private fun home(state: MiniState, scale: Float) {
        val details = if (state == MiniState.Configured) MiniDetails(
            config = WifiConfig(setupComplete = true, networks = listOf(Network(
                basicServiceSets = listOf(
                    BasicServiceSet(ssid = profile.name, band = 2, authKind = AuthKind.WPA2),
                    BasicServiceSet(ssid = profile.name, band = 5, authKind = AuthKind.WPA2),
                ),
            ))),
        ) else MiniDetails()
        compose.show(dark = false, fontScale = scale) {
            HomeScreen(MiniSnapshot(state, details), onRefresh = {}, onConnect = {}, onDetails = {},
                onSetupChoice = {}, animateGuides = false, onGuide = {}, onHelp = {})
        }
        val (name, checkpoints) = when (state) {
            MiniState.SetupPending -> "ready" to listOf(R.string.wifi_secure, R.string.details)
            MiniState.Configured -> "already_set_up" to listOf(
                R.string.home_wifi_status, R.string.home_password_hidden, R.string.home_reset_guide,
            )
            MiniState.Bypass -> "wifi_off" to listOf(R.string.home_reset_guide)
            MiniState.NotFound -> "not_found" to listOf(
                R.string.cable_body, R.string.wifi_body, R.string.join_starlink, R.string.field_reset_help,
            )
            else -> error("Not a screenshot fixture")
        }
        captureWithCheckpoints(name, scale, checkpoints)
        assertLayoutAndTouchTargets()
    }

    private fun reset(scale: Float) {
        compose.show(dark = false, fontScale = scale) {
            ResetGuideScreen({}, animateGuides = false)
        }
        captureWithCheckpoints("reset_guide", scale, listOf(
            R.string.reset_press, R.string.reset_light, R.string.reset_wait, R.string.back,
        ))
        assertLayoutAndTouchTargets()
    }

    private fun password(scale: Float) {
        compose.show(dark = false, fontScale = scale) {
            WifiOnSetupScreen(profile, ownPassword = true, hasSavedProfile = true,
                {}, {}, {}, {}, {}, {}, {})
        }
        captureWithCheckpoints("wifi_password", scale, listOf(
            R.string.wifi_on_use_saved, R.string.wifi_on_show_password, R.string.wifi_on_review,
        ))
        compose.onNodeWithText(text(R.string.wifi_on_show_password)).performScrollTo().performClick()
        compose.onNodeWithText(profile.password).performScrollTo().assertIsDisplayed()
        capture("wifi_password_${suffix(scale)}_visible")
        assertLayoutAndTouchTargets()
    }

    private fun confirm(scale: Float) {
        var confirms = 0
        var cancels = 0
        compose.show(dark = false, fontScale = scale) {
            WifiOnConfirmScreen(profile.name, { confirms++ }, { cancels++ })
        }
        captureWithCheckpoints("wifi_on_confirm", scale, listOf(
            R.string.wifi_on_rejoin, R.string.wifi_dialog_caption, R.string.cancel,
        ))
        assertLayoutAndTouchTargets()
        assertEquals(0, confirms)
        compose.onNodeWithText(text(R.string.cancel)).performScrollTo().performClick()
        assertEquals(1, cancels)
        assertEquals(0, confirms)
        compose.onNodeWithText(text(R.string.wifi_on_confirm)).performScrollTo().performClick()
        assertEquals(1, confirms)
    }

    private fun captureWithCheckpoints(name: String, scale: Float, checkpoints: List<Int>) {
        assertEquals("et", RuntimeEnvironment.getApplication().resources.configuration.locales[0].language)
        capture("${name}_${suffix(scale)}")
        checkpoints.forEachIndexed { index, id ->
            compose.onAllNodesWithText(text(id))[0].performScrollTo().assertIsDisplayed()
            capture("${name}_${suffix(scale)}_step_${index + 1}")
        }
    }

    private fun assertLayoutAndTouchTargets() {
        // Check full text layouts, including content below the viewport, for truncation/overflow.
        compose.onAllNodes(SemanticsMatcher.keyIsDefined(
            SemanticsActions.GetTextLayoutResult,
        ), useUnmergedTree = true).fetchSemanticsNodes().forEach { node ->
            val layouts = mutableListOf<TextLayoutResult>()
            node.config[SemanticsActions.GetTextLayoutResult].action?.invoke(layouts)
            layouts.forEach(::assertTextFits)
        }
        val buttons = compose.onAllNodes(hasClickAction())
        repeat(buttons.fetchSemanticsNodes().size) { index ->
            buttons[index].performScrollTo().assertIsDisplayed()
                .assertHeightIsAtLeast(48.dp).assertWidthIsAtLeast(48.dp)
        }
    }

    private fun text(id: Int) = RuntimeEnvironment.getApplication().getString(id)

    private fun suffix(scale: Float) = "light" + if (scale == 2f) "_font_200" else ""

    private fun capture(name: String) {
        val directory = File(requireNotNull(System.getProperty("et.screenshots.dir")))
        compose.onRoot().captureRoboImage(File(directory, "$name.png").absolutePath)
    }
}
