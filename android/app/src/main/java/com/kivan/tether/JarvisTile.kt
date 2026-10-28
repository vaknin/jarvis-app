package com.kivan.tether

import android.app.PendingIntent
import android.graphics.drawable.Icon
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** The Quick Settings tile "Jarvis": a tap calls, a tap during the call hangs up. */
class JarvisTile : TileService() {
    private var watch: Job? = null

    // While the panel is open the tile follows the call, so a tap that starts one shows it at once.
    override fun onStartListening() {
        watch?.cancel()
        watch = CoroutineScope(Dispatchers.Main).launch { Voice.ui.collect(::show) }
    }

    override fun onStopListening() {
        watch?.cancel()
        watch = null
    }

    private fun show(ui: Voice.Ui) {
        qsTile?.apply {
            state = if (ui.live) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
            label = "Jarvis"
            subtitle = if (ui.live) Voice.label(ui) else "Call"
            icon = Icon.createWithResource(this@JarvisTile, R.drawable.lucide_phone)
            updateTile()
        }
    }

    override fun onClick() {
        if (Voice.ui.value.live) {
            Voice.hangup(this)
            return
        }
        // The call screen starts the call (the mic needs an app on screen to start recording).
        val pi = PendingIntent.getActivity(this, 0, CallActivity.intent(this, start = true), PendingIntent.FLAG_IMMUTABLE)
        startActivityAndCollapse(pi)
    }
}
