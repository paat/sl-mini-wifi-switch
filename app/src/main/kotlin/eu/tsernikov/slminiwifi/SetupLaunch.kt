package eu.tsernikov.slminiwifi

import android.content.Context
import android.content.Intent
import java.util.UUID

/** Both quick entry points open the existing foreground check and choice screen. */
object SetupLaunch {
    const val ACTION = "eu.tsernikov.slminiwifi.action.SET_UP_MINI"

    const val EXTRA_LAUNCH_ID = "setupLaunchId"

    fun intent(context: Context): Intent = Intent(context, MainActivity::class.java).apply {
        action = ACTION
        putExtra(EXTRA_LAUNCH_ID, UUID.randomUUID().toString())
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
    }
}
