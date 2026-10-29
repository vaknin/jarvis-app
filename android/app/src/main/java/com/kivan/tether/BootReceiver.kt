package com.kivan.tether

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * After a reboot or an update the process starts here; [TetherApp] starts the link service. An update
 * mid-call killed the call's process: it comes back here at once ([Voice.resume]).
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        android.util.Log.i("TetherCall", "started by ${intent.action} at ${System.currentTimeMillis()}")
        LinkService.start(context)
        Voice.resume(context)
    }
}
