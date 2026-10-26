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
import com.kivan.tether.ui.ChatScreen

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent { ChatScreen() }
        requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        LinkService.start(this)
        askBatteryExemption()
    }

    override fun onResume() {
        super.onResume()
        Core.setVisible(true)
    }

    override fun onPause() {
        Core.setVisible(false)
        super.onPause()
    }

    /**
     * Once: without it, Doze cuts the network while the screen is off, and the laptop can't reach
     * the phone until it wakes. The always-on link depends on this.
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
