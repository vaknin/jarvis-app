package com.kivan.tether

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.kivan.tether.ui.HomeScreen

/** Home: the big Call button and What's new; the forum and the laptop chat are one tap away. */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent { HomeScreen() }
        requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        askBatteryExemption()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        LinkService.start(this) // here, not in onCreate: also brings back a service killed while the app was open
        Updater.foreground = true
        // From "Update ready": download if needed and ask Android's one question now.
        Updater.onOpen(this, tapped = intent.getBooleanExtra(Updater.EXTRA_INSTALL, false))
        intent.removeExtra(Updater.EXTRA_INSTALL)
    }

    override fun onPause() {
        Updater.foreground = false
        super.onPause()
    }

    /**
     * Once: without it, Doze can cut the network in the middle of the short sync after a wake, and
     * a file from the laptop waits until the screen comes on. It costs nothing while idle.
     */
    @SuppressLint("BatteryLife")
    private fun askBatteryExemption() {
        val pm = getSystemService(PowerManager::class.java)
        val prefs = getSharedPreferences("app", MODE_PRIVATE)
        if (pm.isIgnoringBatteryOptimizations(packageName) || prefs.getBoolean("askedBattery", false)) return
        prefs.edit().putBoolean("askedBattery", true).apply()
        runCatching {
            startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
        }
    }
}
