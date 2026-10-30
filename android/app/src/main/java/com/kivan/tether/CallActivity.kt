package com.kivan.tether

import android.Manifest
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.PowerManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.kivan.tether.core.CallState
import com.kivan.tether.ui.CallScreen
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * The call screen: one big button to call, then Mute, Hang up and the audio route. Shown over the lock screen, and
 * opened with [START] by the Quick Settings tile and the "Call Jarvis" shortcut, which call at once, as an assist request
 * (ACTION_ASSIST) does.
 * While a call is up, the proximity sensor turns the screen off in a pocket, so nothing gets pressed.
 */
class CallActivity : ComponentActivity() {
    private var proximity: PowerManager.WakeLock? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        if (Move.forward(this)) return
        setShowWhenLocked(true)
        setContent { CallScreen(onCall = { call() }, onMute = { Voice.toggleMute(this) }, onHangup = { Voice.hangup(this) }, onRoute = { Voice.nextRoute(this) }, onPause = { Voice.togglePause(this) }, onDecline = { Callback.decline(this); finish() }) }
        if (calls(intent) && savedInstanceState == null) call(intent)
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
        if (calls(intent)) call(intent)
    }

    private fun calls(i: Intent?) = (i?.action == START || i?.action == Intent.ACTION_ASSIST).also { if (it) MediaKeys.opened(this) }

    /** The dropped call a press continues: the one ringing, or the one the intent answers. */
    private var rejoin: String? = null

    /** The tracked task this call is about: its button placed it. */
    private var task: String? = null

    private fun call(i: Intent? = null) {
        rejoin = Callback.answered(this, i)
        task = i?.getStringExtra(TASK)
        if (CallService.micGranted(this)) Voice.start(this, rejoin, task) else requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), MIC)
    }

    // The plain API, as MainActivity: the activity-result one needs a newer Fragment than core-telecom brings.
    @Deprecated("plain permission callback")
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == MIC && CallService.micGranted(this)) Voice.start(this, rejoin, task)
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
        private const val MIC = 7
        private const val TASK = "task"

        fun intent(context: Context, start: Boolean, task: String? = null) = Intent(context, CallActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .apply { if (start) action = START }
            .putExtra(TASK, task)
    }
}
