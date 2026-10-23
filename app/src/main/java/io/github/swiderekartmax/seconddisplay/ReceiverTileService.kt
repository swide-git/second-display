package io.github.swiderekartmax.seconddisplay

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

/**
 * Quick Settings tile: shows whether the receiver runs; a tap stops it, or opens the app,
 * which starts it (a foreground service start from a tile is not reliably allowed).
 */
class ReceiverTileService : TileService() {
    companion object {
        /** Ask System UI to redraw the tile after the receiver started or stopped. */
        fun refresh(context: Context) {
            runCatching {
                requestListeningState(context, ComponentName(context, ReceiverTileService::class.java))
            }
        }
    }

    override fun onStartListening() {
        super.onStartListening()
        val tile = qsTile ?: return
        val running = ReceiverService.isRunning
        tile.state = if (running) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = "Second Display"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) tile.subtitle = if (running) "Receiving" else "Off"
        tile.updateTile()
    }

    override fun onClick() {
        super.onClick()
        if (ReceiverService.isRunning) {
            ReceiverServer.init(applicationContext)
            ReceiverService.stop(this)
            ReceiverServer.stopServer()
            qsTile?.apply {
                state = Tile.STATE_INACTIVE
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) subtitle = "Off"
                updateTile()
            }
            return
        }
        val open = Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startActivityAndCollapse(PendingIntent.getActivity(this, 0, open, PendingIntent.FLAG_IMMUTABLE))
        } else {
            openBeforeAndroid14(open)
        }
    }

    // The PendingIntent overload exists only from Android 14; Android 11-13 have just this one.
    @SuppressLint("StartActivityAndCollapseDeprecated")
    @Suppress("DEPRECATION")
    private fun openBeforeAndroid14(intent: Intent) = startActivityAndCollapse(intent)
}
