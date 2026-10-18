package eu.tsernikov.slminiwifi.ui

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.surfaceColorAtElevation
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertTrue
import org.junit.Test

/** WCAG relative luminance: every enabled body/label color used by our screens is at least AA. */
class ThemeContrastTest {
    @Test fun lightThemeHasAaTextContrast() = checkPalette(LightColors)
    @Test fun darkThemeHasAaTextContrast() = checkPalette(DarkColors)

    private fun checkPalette(colors: ColorScheme) {
        with(colors) {
            val pairs = mapOf(
                "body/background" to (onBackground to background),
                "body/surface" to (onSurface to surface),
                "body/card" to (onSurface to surfaceVariant),
                "secondary body/surface" to (onSurfaceVariant to surface),
                "secondary body/card" to (onSurfaceVariant to surfaceVariant),
                "button" to (onPrimary to primary),
                "container" to (onPrimaryContainer to primaryContainer),
                "outlined button/surface" to (primary to surface),
                "outlined button/card" to (primary to surfaceVariant),
                "dialog body" to (onSurface to surfaceColorAtElevation(2.dp)),
                "dialog action" to (primary to surfaceColorAtElevation(2.dp)),
                "field error" to (error to surface),
            )
            pairs.forEach { (name, pair) ->
                val ratio = contrast(pair.first, pair.second)
                assertTrue("$name contrast is $ratio, expected at least 4.5:1", ratio >= 4.5f)
            }
        }
    }

    private fun contrast(first: Color, second: Color): Float {
        val light = maxOf(first.luminance(), second.luminance())
        val dark = minOf(first.luminance(), second.luminance())
        return (light + 0.05f) / (dark + 0.05f)
    }
}
