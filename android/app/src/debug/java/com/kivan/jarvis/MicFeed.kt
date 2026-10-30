package com.kivan.jarvis

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log

/**
 * Tests (debug builds only): the emulator's mic is silent, so this stands in for the call's meter
 * for a while, at a level from 0 to 100:
 *
 *     adb shell am broadcast -n com.kivan.jarvis.debug/com.kivan.jarvis.MicFeed --ei level 80 --ei ms 8000
 */
class MicFeed : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val level = intent.getIntExtra("level", 80) / 100f
        val ms = intent.getIntExtra("ms", 8000).toLong()
        Log.i("Jarvis", "mic feed: $level, $ms ms")
        val real = MicLevel.source
        MicLevel.source = { level }
        Handler(Looper.getMainLooper()).postDelayed({ MicLevel.source = real }, ms)
    }
}
