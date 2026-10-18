package eu.tsernikov.slminiwifi.ui

import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext

/** Android's Remove animations switch sets this scale to zero. Observe changes while open. */
@Composable
internal fun rememberGuideAnimationsEnabled(requested: Boolean = true): Boolean {
    val resolver = LocalContext.current.contentResolver
    fun animationsEnabled() = Settings.Global.getFloat(
        resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f,
    ) > 0f
    var enabled by remember(resolver) { mutableStateOf(animationsEnabled()) }
    DisposableEffect(resolver) {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) { enabled = animationsEnabled() }
        }
        resolver.registerContentObserver(
            Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE), false, observer,
        )
        // Cover a setting change between composition and observer registration.
        enabled = animationsEnabled()
        onDispose { resolver.unregisterContentObserver(observer) }
    }
    return requested && enabled
}
