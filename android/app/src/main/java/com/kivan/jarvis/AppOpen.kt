package com.kivan.jarvis

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings

/** What the app does when it opens, whichever screen it opens on: the wall, or the old home. */
internal object AppOpen {
    /** The questions Android asks him, once a screen is really up. */
    fun asks(activity: Activity) {
        activity.requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        askBatteryExemption(activity)
    }

    fun resumed(activity: Activity) {
        LinkService.start(activity) // on resume, not create: also brings back a service killed while the app was open
        Updater.foreground = true
        Move.resumed()
    }

    fun paused() {
        Updater.foreground = false
    }

    /**
     * Once: without it, Doze can cut the network in the middle of the short sync after a wake, and
     * a file from the laptop waits until the screen comes on. It costs nothing while idle.
     */
    @SuppressLint("BatteryLife")
    private fun askBatteryExemption(activity: Activity) {
        val pm = activity.getSystemService(PowerManager::class.java)
        val prefs = activity.getSharedPreferences("app", Activity.MODE_PRIVATE)
        if (pm.isIgnoringBatteryOptimizations(activity.packageName) || prefs.getBoolean("askedBattery", false)) return
        prefs.edit().putBoolean("askedBattery", true).apply()
        runCatching {
            activity.startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${activity.packageName}")))
        }
    }
}
