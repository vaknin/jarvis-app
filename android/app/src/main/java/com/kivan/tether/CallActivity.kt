package com.kivan.tether

import android.Manifest
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.PowerManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.kivan.tether.core.CallState
import com.kivan.tether.ui.CallScreen
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * The call screen: one big button to call, then Mute and Hang up. Shown over the lock screen, and
 * opened with [START] by the Quick Settings tile and the "Call Jarvis" shortcut, which call at once.
 * While a call is up, the proximity sensor turns the screen off in a pocket, so nothing gets pressed.
 */
class CallActivity : ComponentActivity() {
    private val askMic = registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) Voice.start(this)
    }
    private var proximity: PowerManager.WakeLock? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true)
        setContent { CallScreen(onCall = ::call, onMute = { Voice.toggleMute(this) }, onHangup = { Voice.hangup(this) }) }
        if (intent?.action == START && savedInstanceState == null) call()
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                Voice.ui.collect { ui ->
                    pocket(ui.live)
                    if (ui.state is CallState.Ended) {
                        // Show why for a moment, then get out of the way.
                        delay(2500)
                        if (Voice.ui.value.state is CallState.Ended) finish()
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.action == START) call()
    }

    private fun call() {
        if (CallService.micGranted(this)) Voice.start(this) else askMic.launch(Manifest.permission.RECORD_AUDIO)
    }

    private fun pocket(on: Boolean) {
        val pm = getSystemService(PowerManager::class.java)
        if (on && proximity == null && pm.isWakeLockLevelSupported(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK)) {
            proximity = pm.newWakeLock(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK, "tether:pocket").apply { acquire() }
        } else if (!on) {
            proximity?.let { if (it.isHeld) it.release() }
            proximity = null
        }
    }

    override fun onStop() {
        pocket(false)
        super.onStop()
    }

    companion object {
        const val START = "com.kivan.tether.CALL"

        fun intent(context: Context, start: Boolean) = Intent(context, CallActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .apply { if (start) action = START }
    }
}
