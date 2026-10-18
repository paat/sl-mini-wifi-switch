package eu.tsernikov.slminiwifi.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import eu.tsernikov.slminiwifi.state.MiniSnapshot
import eu.tsernikov.slminiwifi.state.MiniState
import eu.tsernikov.slminiwifi.state.WifiOffError
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Offline, callback-only rendering: no transport, permissions request, or device access. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "en-rUS-w411dp-h891dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class FieldGuideScreenshotsTest {
    @get:Rule val compose = createComposeRule()

    @Test fun resetLight() = guide(GuideStep.Reset, false)
    @Test fun resetDark() = guide(GuideStep.Reset, true)
    @Test fun resetLargeLight() = guide(GuideStep.Reset, false, 2f)
    @Test fun resetLargeDark() = guide(GuideStep.Reset, true, 2f)
    @Test fun lightLight() = guide(GuideStep.Light, false)
    @Test fun lightDark() = guide(GuideStep.Light, true)
    @Test fun lightLargeLight() = guide(GuideStep.Light, false, 2f)
    @Test fun lightLargeDark() = guide(GuideStep.Light, true, 2f)
    @Test fun cableLight() = guide(GuideStep.Cable, false)
    @Test fun cableDark() = guide(GuideStep.Cable, true)
    @Test fun cableLargeLight() = guide(GuideStep.Cable, false, 2f)
    @Test fun cableLargeDark() = guide(GuideStep.Cable, true, 2f)
    @Test fun wifiLight() = guide(GuideStep.Wifi, false)
    @Test fun wifiDark() = guide(GuideStep.Wifi, true)
    @Test fun wifiLargeLight() = guide(GuideStep.Wifi, false, 2f)
    @Test fun wifiLargeDark() = guide(GuideStep.Wifi, true, 2f)
    @Test fun androidLight() = guide(GuideStep.AndroidConnect, false)
    @Test fun androidDark() = guide(GuideStep.AndroidConnect, true)
    @Test fun androidLargeLight() = guide(GuideStep.AndroidConnect, false, 2f)
    @Test fun androidLargeDark() = guide(GuideStep.AndroidConnect, true, 2f)
    @Test fun checkLight() = guide(GuideStep.Check, false)
    @Test fun checkDark() = guide(GuideStep.Check, true)
    @Test fun checkLargeLight() = guide(GuideStep.Check, false, 2f)
    @Test fun checkLargeDark() = guide(GuideStep.Check, true, 2f)
    @Test fun offlineHelpLight() = help(false)
    @Test fun offlineHelpDark() = help(true)
    @Test fun offlineHelpLargeLight() = help(false, 2f)
    @Test fun offlineHelpLargeDark() = help(true, 2f)
    @Test fun restartingLight() = restarting(false)
    @Test fun restartingDark() = restarting(true)
    @Test fun restartingLargeLight() = restarting(false, 2f)
    @Test fun restartingLargeDark() = restarting(true, 2f)

    @Test fun homeReadyLight() = home(MiniState.SetupPending, false)
    @Test fun homeReadyDark() = home(MiniState.SetupPending, true)
    @Test fun homeNotFoundLight() = home(MiniState.NotFound, false)
    @Test fun homeNotFoundDark() = home(MiniState.NotFound, true)
    @Test fun refusedHelpLight() = error(WifiOffError.Refused, false)
    @Test fun refusedHelpDark() = error(WifiOffError.Refused, true)
    @Test fun unreachableHelpLight() = error(WifiOffError.CantReach, false)
    @Test fun unreachableHelpDark() = error(WifiOffError.CantReach, true)

    @Test fun guideStepChangesStartAtTopAtLargeFontIncludingBackAndAlternatePaths() {
        val step = mutableStateOf(GuideStep.Reset)
        show(false, 2f) { GuideStepScreen(step.value, {}, {}, {}, {}, {}, false) }
        // Forward, alternate connection path, and Back all create fresh scroll state.
        for (next in listOf(GuideStep.Light, GuideStep.Cable, GuideStep.Wifi,
            GuideStep.AndroidConnect, GuideStep.Check, GuideStep.AndroidConnect,
            GuideStep.Wifi, GuideStep.Cable, GuideStep.Light, GuideStep.Reset)) {
            val scroll = compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange))
            scroll.performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, 10_000f) }
            val range = scroll.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange]
            if (range.maxValue() > 0f) org.junit.Assert.assertTrue("${step.value} scrolled", range.value() > 0f)
            compose.runOnIdle { step.value = next }
            compose.onNodeWithText("Back").assertIsDisplayed()
            assertEquals(0f, scroll.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value())
        }
    }

    @Test fun lightAnimationFinishesOffAndStaysOff() {
        compose.mainClock.autoAdvance = false
        show(false) { ResetLightIllustration(animate = true) }
        compose.mainClock.advanceTimeBy(3500)
        val light = compose.onNodeWithContentDescription(lightDescription)
        light.assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Light off"))
        compose.mainClock.advanceTimeBy(4000)
        light.assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Light off"))
    }

    @Test fun reducedMotionShowsOffAndDoesNotClaimHalfFinished() {
        show(false) {
            Screen { ResetLightIllustration(false); WaitingProgress(false) }
        }
        compose.onNodeWithContentDescription(lightDescription)
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Light off"))
        compose.onNodeWithContentDescription("Clock: waiting for the Mini to answer")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.ProgressBarRangeInfo, ProgressBarRangeInfo.Indeterminate))
    }

    private fun home(state: MiniState, dark: Boolean) {
        var guides = 0
        var helps = 0
        show(dark) {
            HomeScreen(MiniSnapshot(state), onRefresh = {}, onConnect = {}, onDetails = {},
                onSetupChoice = {}, animateGuides = false, onGuide = { guides++ }, onHelp = { helps++ })
        }
        val name = "home_${state.name.lowercase()}_${suffix(dark, 1f)}"
        capture(name)
        compose.onNodeWithText("Start here").performScrollTo()
            .assertHeightIsAtLeast(if (state == MiniState.SetupPending) 48.dp else 64.dp).performClick()
        compose.onNodeWithText("Reset help").performScrollTo().assertIsDisplayed().performClick()
        capture("${name}_help")
        assertEquals(1, guides)
        assertEquals(1, helps)
        checkFieldSizesAndText()
    }

    private fun error(error: WifiOffError, dark: Boolean) {
        var helps = 0
        show(dark) { WifiOffErrorScreen(error, {}, animateGuides = false, onHelp = { helps++ }) }
        val name = "error_${error.name.lowercase()}_${suffix(dark, 1f)}"
        capture(name)
        compose.onNodeWithText("Reset help").performScrollTo().assertIsDisplayed().performClick()
        capture("${name}_help")
        assertEquals(1, helps)
        checkFieldSizesAndText()
    }

    private fun guide(step: GuideStep, dark: Boolean, fontScale: Float = 1f) {
        var back = 0
        var next = 0
        var wifi = 0
        var cable = 0
        var connect = 0
        show(dark, fontScale) {
            GuideStepScreen(step, { back++ }, { next++ }, { wifi++ }, { cable++ }, { connect++ }, false)
        }
        val name = "guide_${step.name.lowercase()}_${suffix(dark, fontScale)}"
        capture(name)
        val illustration = when (step) {
            GuideStep.Reset -> "Back of the Mini with the reset symbol circled. A finger presses and holds the symbol."
            GuideStep.Light -> lightDescription
            GuideStep.Cable -> "Phone connected to a USB-C Ethernet adapter, then by a cable to the port on the Mini. The plug moves toward the phone."
            GuideStep.Wifi -> "A drawn phone Wi-Fi list with the open network STARLINK marked by a Wi-Fi symbol. Choose this name."
            GuideStep.AndroidConnect -> "A phone asks to connect to the STARLINK network. The Connect button is at the bottom."
            GuideStep.Check -> "Magnifying glass: find your Mini"
        }
        compose.onNodeWithContentDescription(illustration).performScrollTo().assertIsDisplayed()
        if (fontScale == 2f) capture("${name}_illustration")
        val primary = when (step) {
            GuideStep.Reset -> "I have pressed it"
            GuideStep.Light -> "I have waited"
            GuideStep.Cable -> "The cable is connected"
            GuideStep.Wifi -> "Next"
            GuideStep.AndroidConnect -> "Join STARLINK"
            GuideStep.Check -> "Check my Mini"
        }
        compose.onNodeWithText(primary).performScrollTo().assertIsDisplayed().assertHeightIsAtLeast(64.dp).performClick()
        capture("${name}_actions")
        if (step == GuideStep.AndroidConnect) {
            assertEquals(1, connect)
            compose.onNodeWithText("Next").performScrollTo().performClick()
        }
        assertEquals(1, next)
        if (step == GuideStep.Cable) {
            compose.onNodeWithText("Use Wi-Fi instead").performScrollTo().performClick()
            assertEquals(1, wifi)
        }
        if (step == GuideStep.Wifi) {
            compose.onNodeWithText("Use a cable instead").performScrollTo().performClick()
            assertEquals(1, cable)
        }
        checkFieldSizesAndText()
        compose.onNodeWithText("Back").performScrollTo().performClick()
        assertEquals(1, back)
    }

    private fun help(dark: Boolean, fontScale: Float = 1f) {
        var back = 0
        var checks = 0
        show(dark, fontScale) { ResetGuideScreen({ checks++ }, false, { back++ }) }
        val name = "offline_help_${suffix(dark, fontScale)}"
        capture(name)
        compose.onNodeWithContentDescription(lightDescription).performScrollTo().assertIsDisplayed()
        capture("${name}_light")
        compose.onNodeWithContentDescription(restartDescription).performScrollTo().assertIsDisplayed()
        capture("${name}_restart")
        compose.onNodeWithText("Check again").performScrollTo().assertHeightIsAtLeast(64.dp).performClick()
        capture("${name}_actions")
        checkFieldSizesAndText()
        compose.onNodeWithText("Back").performScrollTo().performClick()
        assertEquals(1, back)
        assertEquals(1, checks)
    }

    private fun restarting(dark: Boolean, fontScale: Float = 1f) {
        show(dark, fontScale) {
            SetupProgressScreen(eu.tsernikov.slminiwifi.R.string.wifi_on_progress_title,
                eu.tsernikov.slminiwifi.R.string.wifi_on_progress_body,
                eu.tsernikov.slminiwifi.R.string.wifi_on_progress_time,
                eu.tsernikov.slminiwifi.R.string.wifi_on_progress_help, false)
        }
        val name = "restarting_${suffix(dark, fontScale)}"
        capture(name)
        compose.onNodeWithContentDescription(restartDescription).performScrollTo().assertIsDisplayed()
        if (fontScale == 2f) capture("${name}_illustration")
        compose.onNodeWithText("Keep the Mini powered on. We will check for up to 3 minutes. If it takes longer, we will show you how to reconnect. We will not send the change again.")
            .performScrollTo().assertIsDisplayed()
        capture("${name}_help")
    }

    private fun checkFieldSizesAndText() {
        val actions = compose.onAllNodes(hasClickAction())
        repeat(actions.fetchSemanticsNodes().size) {
            actions[it].performScrollTo().assertIsDisplayed().assertHeightIsAtLeast(48.dp).assertWidthIsAtLeast(48.dp)
        }
        compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsActions.GetTextLayoutResult), true)
            .fetchSemanticsNodes().forEach { node ->
                val results = mutableListOf<TextLayoutResult>()
                node.config[SemanticsActions.GetTextLayoutResult].action?.invoke(results)
                results.forEach(::assertTextFits)
            }
    }

    private fun show(dark: Boolean, fontScale: Float = 1f, content: @Composable () -> Unit) {
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, fontScale)) {
                MiniTheme(darkTheme = dark, content = content)
            }
        }
    }

    private fun capture(name: String) {
        val directory = File(requireNotNull(System.getProperty("m4.screenshots.dir")))
        compose.onRoot().captureRoboImage(File(directory, "$name.png").absolutePath)
    }

    private fun suffix(dark: Boolean, fontScale: Float): String =
        (if (dark) "dark" else "light") + if (fontScale == 2f) "_font_200" else ""

    private val lightDescription = "Back of the Mini. Its light blinks quickly, then stays off. An off light alone does not mean the Mini is ready."
    private val restartDescription = "The Mini with a circular arrow: restarting. Keep its power connected while it starts again."
}
