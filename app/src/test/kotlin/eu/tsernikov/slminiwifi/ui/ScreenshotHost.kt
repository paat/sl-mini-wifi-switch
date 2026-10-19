package eu.tsernikov.slminiwifi.ui

import androidx.compose.material3.LocalRippleConfiguration
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.unit.Density

/**
 * Theme, font scale, and no pressed ripple. Ripple fades follow the wall clock and
 * flake verifyRoborazziDebug when a shot is captured after a click.
 */
internal fun ComposeContentTestRule.show(
    dark: Boolean,
    fontScale: Float = 1f,
    content: @Composable () -> Unit,
) {
    setContent {
        CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, fontScale)) {
            // Pressed-state ripples use wall-clock fade and change between runs.
            MiniTheme(darkTheme = dark) {
                CompositionLocalProvider(LocalRippleConfiguration provides null, content = content)
            }
        }
    }
}
