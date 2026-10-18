package eu.tsernikov.slminiwifi

import android.app.Activity
import android.os.Bundle

/**
 * Android adds CLEAR_TASK to static shortcuts. Keep that separate from the main task so
 * opening the shortcut cannot destroy an active setup verification or its recovery state.
 */
class SetupShortcutActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Construct a fresh intent: never forward data or extras from the exported entry point.
        startActivity(SetupLaunch.intent(this))
        finish()
    }
}
