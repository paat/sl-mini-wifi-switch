package eu.tsernikov.slminiwifi.ui

import android.provider.Settings
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import eu.tsernikov.slminiwifi.R
import eu.tsernikov.slminiwifi.data.Profile
import eu.tsernikov.slminiwifi.state.MiniSnapshot
import eu.tsernikov.slminiwifi.state.MiniState
import eu.tsernikov.slminiwifi.state.WifiOffError
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.Config

/** Small field phone: every action must remain reachable with doubled type, without label clipping. */
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "en-rUS-w360dp-h640dp-xhdpi")
class FieldAccessibilityTest {
    @get:Rule val compose = createComposeRule()
    private val profile = Profile("Field Mini", "aSafePassword123")

    @Test fun homeReadyNormal() = home(MiniState.SetupPending, 1f)
    @Test fun homeReadyLargeType() = home(MiniState.SetupPending, 2f)
    @Test fun homeNotFoundNormal() = home(MiniState.NotFound, 1f)
    @Test fun homeNotFoundLargeType() = home(MiniState.NotFound, 2f)
    @Test fun wifiOffConfirmNormal() = offConfirm(1f)
    @Test fun wifiOffConfirmLargeType() = offConfirm(2f)
    @Test fun wifiOffResultNormal() = offResult(1f)
    @Test fun wifiOffResultLargeType() = offResult(2f)
    @Test fun wifiOnSetupNormal() = onSetup(1f)
    @Test fun wifiOnSetupLargeType() = onSetup(2f)
    @Test fun wifiOnConfirmNormal() = onConfirm(1f)
    @Test fun wifiOnConfirmLargeType() = onConfirm(2f)
    @Test fun wifiOnResultNormal() = onResult(1f)
    @Test fun wifiOnResultLargeType() = onResult(2f)

    @Test fun alreadySetupErrorHasOfflineHelpAtLargeType() = error(WifiOffError.AlreadySetUp)
    @Test fun refusedErrorHasOfflineHelpAtLargeType() = error(WifiOffError.Refused)
    @Test fun unreachableErrorHasOfflineHelpAtLargeType() = error(WifiOffError.CantReach)

    @Test fun reconnectOfferRemainsReachableAtLargeType() {
        show(2f) { WifiOnProgressScreen(onReconnect = {}) }
        checkActions(listOf("Try connecting again"))
    }

    @Test fun passwordStorageErrorHasOfflineHelpAtLargeType() {
        var help = 0
        show(2f) {
            WifiOnSetupScreen(profile, false, true, {}, {}, {}, {}, {}, {}, {},
                message = R.string.wifi_on_store_before_setup_failed, onHelp = { help++ })
        }
        checkActions(listOf("Review this choice"))
        compose.onNodeWithText("Show reset steps").performScrollTo().performClick()
        assertEquals(1, help)
    }

    @Test fun unverifiedWifiResultHasOfflineHelpAtLargeType() = resultError(verified = false)
    @Test fun phoneConnectionErrorHasOfflineHelpAtLargeType() = resultError(verified = true)

    private fun error(error: WifiOffError) {
        var help = 0
        var next = 0
        show(2f) { WifiOffErrorScreen(error, { next++ }, animateGuides = false, onHelp = { help++ }) }
        val primary = if (error == WifiOffError.AlreadySetUp) "Show reset steps" else "Reconnect to Mini"
        checkActions(listOf(primary))
        compose.onNodeWithText("Reset help").performScrollTo().performClick()
        assertEquals(1, help)
        compose.onNodeWithText(primary).performScrollTo().performClick()
        assertEquals(1, next)
    }

    private fun resultError(verified: Boolean) {
        var help = 0
        show(2f) {
            WifiOnResultScreen(profile, {}, {}, {}, verified = verified,
                connectionMessage = if (verified) R.string.wifi_on_connect_failed else null,
                onHelp = { help++ })
        }
        checkActions(listOf("Join on this phone"))
        compose.onNodeWithText("Show reset steps").performScrollTo().performClick()
        assertEquals(1, help)
    }

    @Test fun removeAnimationsUpdatesWhileScreenIsOpen() {
        lateinit var resolver: android.content.ContentResolver
        compose.setContent {
            resolver = LocalContext.current.contentResolver
            Text(if (rememberGuideAnimationsEnabled()) "moving" else "final frame")
        }
        val previous = Settings.Global.getFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
        try {
            compose.runOnIdle { Settings.Global.putFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 0f) }
            compose.waitForIdle()
            compose.onNodeWithText("final frame").assertIsDisplayed()
            compose.runOnIdle { Settings.Global.putFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) }
            compose.waitForIdle()
            compose.onNodeWithText("moving").assertIsDisplayed()
        } finally {
            Settings.Global.putFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, previous)
        }
    }

    @Test fun explicitStillFrameOverridesSystemAnimationSetting() {
        compose.setContent { Text(if (rememberGuideAnimationsEnabled(false)) "moving" else "final frame") }
        compose.onNodeWithText("final frame").assertIsDisplayed()
    }

    private fun home(state: MiniState, fontScale: Float) {
        show(fontScale) {
            HomeScreen(MiniSnapshot(state), onRefresh = {}, onConnect = {}, onDetails = {},
                onSetupChoice = {}, animateGuides = false, onGuide = {}, onHelp = {}, onShowCredentials = {})
        }
        checkActions(if (state == MiniState.SetupPending)
            listOf("Wi-Fi off", "Wi-Fi on with password")
        else listOf("Start here", "Check again", "Join STARLINK"))
    }

    private fun offConfirm(fontScale: Float) {
        show(fontScale) { WifiOffConfirmScreen(true, {}, {}, animateGuides = false) }
        checkActions(listOf("Turn Wi-Fi off"))
    }

    private fun offResult(fontScale: Float) {
        show(fontScale) { WifiOffResultScreen(false, {}, {}, animateGuides = false) }
        checkActions(listOf("Done"))
    }

    private fun onSetup(fontScale: Float) {
        show(fontScale) {
            WifiOnSetupScreen(profile, false, true, {}, {}, {}, {}, {}, {}, {})
        }
        checkActions(listOf("Review this choice"))
    }

    private fun onConfirm(fontScale: Float) {
        show(fontScale) { WifiOnConfirmScreen(profile.name, {}, {}) }
        checkActions(listOf("Turn Wi-Fi on"))
    }

    private fun onResult(fontScale: Float) {
        show(fontScale) { WifiOnResultScreen(profile, {}, {}, {}) }
        checkActions(listOf("Join on this phone"))
    }

    private fun show(fontScale: Float, content: @Composable () -> Unit) {
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, fontScale)) {
                MiniTheme(darkTheme = false, content = content)
            }
        }
    }

    private fun checkActions(primaryLabels: List<String>) {
        primaryLabels.forEach {
            compose.onNodeWithText(it).performScrollTo().assertIsDisplayed().assertHeightIsAtLeast(64.dp)
        }
        val actions = compose.onAllNodes(hasClickAction())
        val count = actions.fetchSemanticsNodes().size
        assertTrue("Screen should have a next action", count > 0)
        repeat(count) { index ->
            actions[index].performScrollTo().assertIsDisplayed()
                .assertHeightIsAtLeast(48.dp).assertWidthIsAtLeast(48.dp)
        }
        val layouts = compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsActions.GetTextLayoutResult),
            useUnmergedTree = true).fetchSemanticsNodes()
        assertTrue("Screen should have readable labels", layouts.isNotEmpty())
        layouts.forEach { node ->
            // Single-line editable fields scroll their value horizontally by design.
            if (node.config.contains(SemanticsProperties.EditableText)) return@forEach
            val results = mutableListOf<TextLayoutResult>()
            node.config[SemanticsActions.GetTextLayoutResult].action?.invoke(results)
            results.forEach(::assertTextFits)
        }
    }
}
