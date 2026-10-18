package eu.tsernikov.slminiwifi

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

/** A launcher, never a settings toggle: all network work belongs to the foreground activity. */
class SetupTileService : TileService() {
    override fun onStartListening() {
        super.onStartListening()
        qsTile?.apply {
            state = Tile.STATE_INACTIVE
            label = getString(R.string.setup_launch_label)
            contentDescription = getString(R.string.setup_tile_description)
            updateTile()
        }
    }

    // The Intent overload is required below API 34; the guarded newer branch uses PendingIntent.
    @SuppressLint("StartActivityAndCollapseDeprecated")
    @Suppress("DEPRECATION")
    override fun onClick() {
        super.onClick()
        val intent = SetupLaunch.intent(this)
        if (Build.VERSION.SDK_INT >= 34) {
            startActivityAndCollapse(PendingIntent.getActivity(
                this, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            ))
        } else {
            startActivityAndCollapse(intent)
        }
    }
}
