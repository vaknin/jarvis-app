package com.kivan.jarvis

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import android.content.pm.PackageManager
import com.kivan.jarvis.tabs.TabsActivity
import com.kivan.jarvis.ui.GuideScreen
import com.kivan.jarvis.ui.HomeScreen
import com.kivan.jarvis.ui.MoveInScreen

/**
 * The launcher's door: first-run steps (the move, the guide), then the wall. The old home, with the big
 * Call button and What's new, opens only with [HOME], from the wall's menu.
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        if (toWall(intent)) return
        setContent {
            val state by Move.state.collectAsState()
            val guided by Move.done.collectAsState()
            when {
                state != Move.State.LIVE -> MoveInScreen(allowed, ::allow)
                !guided -> GuideScreen()
                intent.getBooleanExtra(HOME, false) -> HomeScreen()
                // The first run just ended here.
                else -> LaunchedEffect(Unit) { toWall(intent) }
            }
        }
        if (Move.state.value != Move.State.LIVE) return allow()
        if (!Move.done.value) return
        AppOpen.asks(this)
    }

    /** Past the first run the app opens on the wall; nothing of this screen shows. */
    private fun toWall(i: Intent): Boolean {
        if (i.getBooleanExtra(HOME, false) || Move.state.value != Move.State.LIVE || !Move.done.value) return false
        startActivity(wallIntent(this, i.getBooleanExtra(Updater.EXTRA_INSTALL, false)))
        finish()
        return true
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
        toWall(intent)
    }

    override fun onResume() {
        super.onResume()
        AppOpen.resumed(this)
        // Back from Android's settings: go on if they're there now. Asking again is the screen's button, never this.
        if (Move.state.value == Move.State.STANDBY && !allowed && CALLS_NEED.all { checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }) allow()
        // From "Update ready": download if needed and ask Android's one question now.
        Updater.onOpen(this, tapped = intent.getBooleanExtra(Updater.EXTRA_INSTALL, false))
        intent.removeExtra(Updater.EXTRA_INSTALL)
    }

    override fun onPause() {
        AppOpen.paused()
        super.onPause()
    }

    companion object {
        private val CALLS_NEED = arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS)
        private const val HOME = "home"

        /** The old home, kept as the wall's fallback. */
        fun home(context: Context) = Intent(context, MainActivity::class.java).putExtra(HOME, true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        /** Where the launcher and everything that opens the app lands. */
        internal fun wallIntent(context: Context, install: Boolean) = TabsActivity.opening(context).apply {
            if (install) putExtra(Updater.EXTRA_INSTALL, true)
        }
    }
}
