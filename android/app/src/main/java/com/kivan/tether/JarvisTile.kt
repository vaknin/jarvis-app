package com.kivan.tether

import android.app.PendingIntent
import android.graphics.drawable.Icon
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

/** The Quick Settings tile "Jarvis": a tap calls, a tap during the call hangs up. */
class JarvisTile : TileService() {
    override fun onStartListening() {
        val ui = Voice.ui.value
        qsTile?.apply {
            state = if (ui.live) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
            label = "Jarvis"
            subtitle = if (ui.live) Voice.label(ui.state) else "Call"
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
