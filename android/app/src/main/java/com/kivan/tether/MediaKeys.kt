package com.kivan.tether

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent

/**
 * The AirPods' stem press calls Jarvis as the tile does, while nothing plays.
 *
 * Android gives media keys to the media session of the app that last played audio; once that session is gone, to the
 * media-button receiver it left, which it also remembers across a reboot (AOSP MediaSessionService,
 * MediaSessionStack, MediaButtonReceiverHolder). So a call holds a session while its audio plays, which makes Tether
 * that app, and drops it when the call ends: from then on the keys come to [Receiver], until another media app plays.
 * When headphones connect and nothing plays, [claim] makes Tether that app again, so a paused YouTube from yesterday
 * doesn't keep the AirPods. The receiver, not a live session, because the system sends it with leave to open an
 * activity; a session's callback has none. Bluetooth (AVRCP) keys go straight to the media sessions, so an
 * accessibility key filter never sees them.
 *
 * Which key: with nothing playing the AirPods send PLAY for a single and a double press alike (the Pixel's
 * `dumpsys bluetooth_manager`, 2026-10-29: twelve double-presses, twelve `KEYCODE_MEDIA_PLAY`, "internal active player
 * is null"), so a press can't be told from a double-press. Other headsets send NEXT or PLAY_PAUSE; all three call.
 */
object MediaKeys {
    private const val TAG = "TetherKeys"
    private const val PREF = "double_press_call"
    private const val FALLBACK_ID = 30
    private val CALLS = setOf(KeyEvent.KEYCODE_MEDIA_NEXT, KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)

    /** The switch in home's menu, "Double-press to call Jarvis"; on unless turned off. */
    fun enabled(context: Context) = prefs(context).getBoolean(PREF, true)

    fun setEnabled(context: Context, on: Boolean) = prefs(context).edit().putBoolean(PREF, on).apply()

    private fun prefs(context: Context) = context.getSharedPreferences("app", Context.MODE_PRIVATE)

    /** When the call screen last took a START; the receiver checks that its launch landed. */
    @Volatile var screenAt = 0L
    @Volatile private var pressedAt = 0L

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

    /**
     * Makes Tether the app that last played, as a call does: a session PLAYING over 300 ms of silence, then released,
     * so the keys come to [Receiver]. Never while anything plays or a call (any app's) is on, so it takes no keys from
     * a player in use.
     */
    fun claim(context: Context, why: String) {
        val am = context.getSystemService(AudioManager::class.java)
        if (!enabled(context) || Voice.ui.value.live || am.isMusicActive || am.mode != AudioManager.MODE_NORMAL) {
            Log.i(TAG, "claim on $why: skipped (${if (am.isMusicActive) "something plays" else "off, or a call"})")
            return
        }
        // Made here, on the main thread: a session's callback needs a looper.
        val session = session(context) ?: return
        Thread({
            val rate = 16000
            val track = runCatching {
                AudioTrack.Builder()
                    .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                    .setAudioFormat(AudioFormat.Builder().setSampleRate(rate).setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                    .setTransferMode(AudioTrack.MODE_STATIC)
                    .setBufferSizeInBytes(rate * 2 * 3 / 10)
                    .build()
            }.getOrNull()
            try {
                track?.write(ShortArray(rate * 3 / 10), 0, rate * 3 / 10)
                track?.play()
                Thread.sleep(600)
            } catch (e: Exception) {
                Log.w(TAG, "claim: no silence played", e)
            } finally {
                track?.release()
                session.release()
            }
            Log.i(TAG, "claim on $why: done")
        }, "tether-keys-claim").start()
    }

    /** Claims the keys whenever headphones connect (AirPods, any Bluetooth or wired headset). Held by [LinkService]. */
    fun watchHeadphones(context: Context): AudioDeviceCallback {
        val am = context.getSystemService(AudioManager::class.java)
        val cb = object : AudioDeviceCallback() {
            // Android reports the devices already there at once; only a new connection claims.
            private var first = true

            override fun onAudioDevicesAdded(added: Array<out AudioDeviceInfo>) {
                if (first) { first = false; return }
                val d = added.firstOrNull { it.isSink && it.type in HEADPHONES } ?: return
                Handler(Looper.getMainLooper()).postDelayed({ claim(context, "${d.productName} connected") }, 2000)
            }
        }
        am.registerAudioDeviceCallback(cb, Handler(Looper.getMainLooper()))
        return cb
    }

    private val HEADPHONES = setOf(
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_BLE_HEADSET, AudioDeviceInfo.TYPE_WIRED_HEADSET,
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_USB_HEADSET,
    )

    /** Media keys while no app holds a session: NEXT, PLAY or PLAY_PAUSE call Jarvis, the rest are dropped. */
    class Receiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val key = intent.getParcelableExtra(Intent.EXTRA_KEY_EVENT, KeyEvent::class.java) ?: return
            if (key.action != KeyEvent.ACTION_DOWN || key.repeatCount > 0) return
            val name = KeyEvent.keyCodeToString(key.keyCode)
            if (key.keyCode !in CALLS) {
                Log.i(TAG, "$name: not a call key, dropped")
                return
            }
            val now = SystemClock.elapsedRealtime()
            // A slow double-press comes as two PLAYs half a second apart: one call.
            val again = now - pressedAt < 3000
            val live = Voice.ui.value.live
            val why = when {
                live -> "a call is on, ignored"
                !enabled(context) -> "switched off"
                again -> "pressed again, one call"
                else -> "calling"
            }
            Log.i(TAG, "$name from ${intent.getStringExtra(Intent.EXTRA_PACKAGE_NAME)}: $why")
            if (live || !enabled(context) || again) return
            pressedAt = now
            // Jarvis ringing: the press answers (the call screen continues the call it rang for).
            context.startActivity(CallActivity.intent(context, start = true).putExtra(Callback.CALL, Callback.pending()))
            // If Android kept the call screen from opening (a background launch it blocked), ask with a notification,
            // which opens by itself on a locked phone. The launch works on the emulator's SDK 36; SDK 37 is untested.
            val done = goAsync()
            Handler(Looper.getMainLooper()).postDelayed({
                try {
                    if (screenAt < now && !Voice.ui.value.live) {
                        Log.w(TAG, "the call screen didn't open: notified instead")
                        fallback(context)
                    }
                } finally {
                    done.finish()
                }
            }, 3000)
        }
    }

    private fun fallback(context: Context) {
        val open = PendingIntent.getActivity(
            context, 3, CallActivity.intent(context, start = true), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = Notification.Builder(context, Notifier.JARVIS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Call Jarvis")
            .setContentText("Tap to call")
            .setCategory(Notification.CATEGORY_CALL)
            .setContentIntent(open)
            .setFullScreenIntent(open, true)
            .setAutoCancel(true)
            .setTimeoutAfter(60_000)
            .build()
        runCatching { context.getSystemService(NotificationManager::class.java).notify(FALLBACK_ID, n) }
    }

    /** The call screen opened: the fallback notification, if any, has done its job. */
    fun opened(context: Context) {
        screenAt = SystemClock.elapsedRealtime()
        runCatching { context.getSystemService(NotificationManager::class.java).cancel(FALLBACK_ID) }
    }
}
