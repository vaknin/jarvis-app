package com.kivan.jarvis

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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import android.content.pm.PackageManager
import com.kivan.jarvis.ui.GuideScreen
import com.kivan.jarvis.ui.HomeScreen
import com.kivan.jarvis.ui.MoveInScreen

/** Home: the big Call button and What's new; the forum and the laptop chat are one tap away. */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            val state by Move.state.collectAsState()
            val guided by Move.done.collectAsState()
            when {
                state != Move.State.LIVE -> MoveInScreen(allowed, ::allow)
                !guided -> GuideScreen()
                else -> HomeScreen()
            }
        }
        if (Move.state.value != Move.State.LIVE) return allow()
        if (!Move.done.value) return
        requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        askBatteryExemption()
    }

    private var allowed by mutableStateOf(true)

    /** The move needs both before it starts: a call must work the moment this app has the link. */
    private fun allow() {
        val missing = CALLS_NEED.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        allowed = missing.isEmpty()
        when {
            allowed -> if (!Move.yielded()) Move.start(this)
            missing.none { shouldShowRequestPermissionRationale(it) } && asked ->
                startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
            else -> {
                asked = true
                requestPermissions(missing.toTypedArray(), 2)
            }
        }
    }

    private var asked = false

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, results: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, results)
        if (requestCode == 2) allowed = CALLS_NEED.all { checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }
        if (requestCode == 2 && allowed && !Move.yielded()) Move.start(this)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        LinkService.start(this) // here, not in onCreate: also brings back a service killed while the app was open
        Updater.foreground = true
        Move.resumed()
        // Back from Android's settings: go on if they're there now. Asking again is the screen's button, never this.
        if (Move.state.value == Move.State.STANDBY && !allowed && CALLS_NEED.all { checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }) allow()
        // From "Update ready": download if needed and ask Android's one question now.
        Updater.onOpen(this, tapped = intent.getBooleanExtra(Updater.EXTRA_INSTALL, false))
        intent.removeExtra(Updater.EXTRA_INSTALL)
    }

    override fun onPause() {
        Updater.foreground = false
        super.onPause()
    }

    private companion object {
        val CALLS_NEED = arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS)
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
