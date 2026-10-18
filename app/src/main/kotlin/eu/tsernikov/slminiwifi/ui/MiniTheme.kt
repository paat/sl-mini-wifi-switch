package eu.tsernikov.slminiwifi.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

internal val LightColors = lightColorScheme(
    primary = Color(0xFF005B52),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFB5F2E5),
    onPrimaryContainer = Color(0xFF00201C),
    background = Color(0xFFF7FAF8),
    onBackground = Color(0xFF17201D),
    surface = Color(0xFFF7FAF8),
    onSurface = Color(0xFF17201D),
    surfaceVariant = Color(0xFFE0EAE5),
    onSurfaceVariant = Color(0xFF3E4944),
)
internal val DarkColors = darkColorScheme(
    primary = Color(0xFF91D5C7),
    onPrimary = Color(0xFF00372F),
    primaryContainer = Color(0xFF005047),
    onPrimaryContainer = Color(0xFFB5F2E5),
    background = Color(0xFF101916),
    onBackground = Color(0xFFE1EAE4),
    surface = Color(0xFF101916),
    onSurface = Color(0xFFE1EAE4),
    surfaceVariant = Color(0xFF3E4944),
    onSurfaceVariant = Color(0xFFC1CEC6),
)

/** Explicit palettes also make both themes reproducible in offline screenshot tests. */
@Composable
fun MiniTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (darkTheme) DarkColors else LightColors, content = content)
}
