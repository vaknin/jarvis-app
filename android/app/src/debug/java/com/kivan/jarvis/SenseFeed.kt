package com.kivan.jarvis

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Tests (debug builds only): the emulator has no Lark and no AirPods, so this stands in for
 * them. Each extra is optional; none clears it:
 *
 *     adb shell am broadcast -n com.kivan.jarvis.debug/com.kivan.jarvis.SenseFeed --ez airpods true
 */
class SenseFeed : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        fun flag(name: String) = if (intent.hasExtra(name)) intent.getBooleanExtra(name, false) else null
        Senses.fake = Senses.Fake(flag("lark"), flag("airpods")).takeIf { it != Senses.Fake() }
    }
}
