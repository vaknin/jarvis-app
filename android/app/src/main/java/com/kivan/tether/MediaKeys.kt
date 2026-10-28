package com.kivan.tether

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.util.Log
import android.view.KeyEvent

/**
 * The AirPods' stem double-press, which reaches the phone as the media "next" key, calls Jarvis as the tile does.
 *
 * Android gives media keys to the media session of the app that last played audio; once that session is gone, to the
 * media-button receiver it left, which it also remembers across a reboot (AOSP MediaSessionService,
 * MediaSessionStack, MediaButtonReceiverHolder). So a call holds a session while its audio plays, which makes Tether
 * that app, and drops it when the call ends: from then on "next" comes to [Receiver], until another media app plays.
 * The receiver, not a live session, because the system sends it with leave to open an activity; a session's callback
 * has none. Bluetooth (AVRCP) keys go straight to the media sessions, so an accessibility key filter never sees them.
 */
object MediaKeys {
    private const val TAG = "TetherKeys"
    private const val PREF = "double_press_call"

    /** The switch in home's menu, "Double-press to call Jarvis"; on unless turned off. */
    fun enabled(context: Context) = prefs(context).getBoolean(PREF, true)

    fun setEnabled(context: Context, on: Boolean) = prefs(context).edit().putBoolean(PREF, on).apply()

    private fun prefs(context: Context) = context.getSharedPreferences("app", Context.MODE_PRIVATE)

    /** For the length of a call: makes Tether the app media keys go to, and swallows them meanwhile. */
    fun session(context: Context): MediaSession? {
        if (!enabled(context)) return null
        return runCatching {
            MediaSession(context, "jarvis-call").apply {
                setMediaButtonBroadcastReceiver(ComponentName(context, Receiver::class.java))
                setCallback(object : MediaSession.Callback() {
                    override fun onMediaButtonEvent(intent: Intent) = true
                })
                setPlaybackState(PlaybackState.Builder().setState(PlaybackState.STATE_PLAYING, 0, 1f).build())
                isActive = true
            }
        }.onFailure { Log.w(TAG, "no media session", it) }.getOrNull()
    }

    /** Media keys while no app holds a session: "next" calls Jarvis, the rest are dropped. */
    class Receiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val key = intent.getParcelableExtra(Intent.EXTRA_KEY_EVENT, KeyEvent::class.java) ?: return
            if (key.keyCode != KeyEvent.KEYCODE_MEDIA_NEXT || key.action != KeyEvent.ACTION_DOWN || key.repeatCount > 0) return
            val live = Voice.ui.value.live
            Log.i(TAG, "next: ${if (live) "a call is on, ignored" else if (!enabled(context)) "switched off" else "calling"}")
            if (live || !enabled(context)) return
            context.startActivity(CallActivity.intent(context, start = true))
        }
    }
}
