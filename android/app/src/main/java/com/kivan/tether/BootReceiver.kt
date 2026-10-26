package com.kivan.tether

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** After a reboot or an update the process starts here; [TetherApp] starts the link service. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        LinkService.start(context)
    }
}
