package eu.tsernikov.slminiwifi

import android.content.Context
import android.content.Intent

/** Both quick entry points open the existing foreground check and choice screen. */
object SetupLaunch {
    const val ACTION = "eu.tsernikov.slminiwifi.action.SET_UP_MINI"

    fun intent(context: Context): Intent = Intent(context, MainActivity::class.java).apply {
        action = ACTION
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
    }
}
