package com.kivan.jarvis

import android.Manifest
import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.PowerManager
import androidx.activity.ComponentActivity
import androidx.activity.addCallback
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.kivan.jarvis.core.CallState
import com.kivan.jarvis.tabs.TabsActivity
import com.kivan.jarvis.ui.CallScreen
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * The call in a tab: Mute, Hang up, the audio route, the timer. Shown over the lock screen. A tab's Call
 * button opens it with [START] and that tab; the stem, the Quick Settings tile, the "Call Jarvis"
 * shortcut and an assist request (ACTION_ASSIST) call at once in a new tab. A ring answered goes where
 * the server says. Back goes to the tab; the call stays up and the tab shows it.
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
        onBackPressedDispatcher.addCallback(this) {
            // Over the lock screen there's nothing to go to.
            if (!getSystemService(KeyguardManager::class.java).isKeyguardLocked) startActivity(TabsActivity.opening(this@CallActivity, Voice.place.value.tab))
            finish()
        }
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

    /** The tab this call is in: [Voice.start]'s. */
    private var tab: String? = null
    private var name = ""

    private fun call(i: Intent? = null) {
        // Asked before [Callback.answered], which stops the ring.
        val ring = Callback.ringing != null || i?.hasExtra(Callback.CALL) == true
        rejoin = Callback.answered(this, i)
        task = i?.getStringExtra(TASK)
        tab = callTab(ring || rejoin != null, i?.hasExtra(TAB) == true, i?.getStringExtra(TAB))
        name = i?.getStringExtra(NAME).orEmpty()
        if (CallService.micGranted(this)) Voice.start(this, rejoin, task, tab, name) else requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), MIC)
    }

    // The plain API, as MainActivity: the activity-result one needs a newer Fragment than core-telecom brings.
    @Deprecated("plain permission callback")
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == MIC && CallService.micGranted(this)) Voice.start(this, rejoin, task, tab, name)
    }

    private fun pocket(on: Boolean) {
        val pm = getSystemService(PowerManager::class.java)
        if (on && proximity == null && pm.isWakeLockLevelSupported(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK)) {
            proximity = pm.newWakeLock(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK, "jarvis:pocket").apply { acquire() }
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
        const val START = "com.kivan.jarvis.CALL"
        private const val MIC = 7
        private const val TASK = "task"
        private const val TAB = "tab"
        private const val NAME = "name"

        /** `tab`: the tab the call is in, [Voice.NEW_TAB] for a new one, null for the server to say. */
        fun intent(context: Context, start: Boolean, task: String? = null, tab: String? = Voice.NEW_TAB, name: String = "") =
            Intent(context, CallActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .apply { if (start) action = START }
                .putExtra(TASK, task)
                .putExtra(TAB, tab)
                .putExtra(NAME, name)

        /**
         * The tab a press lands in. A ring or a dropped call's rejoin is the server's to place; the
         * rest without a tab of their own (the shortcut, an assist request) are a new tab.
         */
        internal fun callTab(answers: Boolean, has: Boolean, tab: String?): String? = when {
            answers -> null
            !has -> Voice.NEW_TAB
            else -> tab
        }
    }
}
