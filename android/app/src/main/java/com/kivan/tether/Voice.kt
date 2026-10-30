package com.kivan.tether

import android.content.Context
import android.content.Intent
import android.service.quicksettings.TileService
import android.content.ComponentName
import com.kivan.tether.core.CallState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

/**
 * A voice call with Jarvis, as the screens see it. The call itself lives in [CallService]; the
 * wire, the jitter buffer, the earcons and the reconnect are Rust (crates/voice, through the ffi's
 * `VoiceCall`). It reaches `jarvis-voice` on dibs through the forum tunnel's voice port.
 */
object Voice {
    /** The forum tunnel's second port: its pipe opens with `voice`, which the gate sends to jarvis-voice. */
    const val PORT: UShort = 8791u

    data class Ui(
        /** Null when there's no call. */
        val state: CallState? = null,
        /** Muted by a tap: the mic is cut. */
        val muted: Boolean = false,
        /** Muted by voice ("mute my mic"): the mic keeps streaming, the server hears only "unmute". A tap lifts it. */
        val voiceMuted: Boolean = false,
        /** Jarvis's reply is paused (the button, or "stop" said on the call); "continue" goes on. */
        val paused: Boolean = false,
        val error: String? = null,
        /** Where the call sounds now, and every place it can (the route button cycles them). */
        val route: AudioRoute? = null,
        val routes: List<AudioRoute> = emptyList(),
        /** When the call first connected (wall clock, ms): the duration counts from it, through every turn and reconnect. */
        val connectedAt: Long? = null,
        /** Jarvis is ringing ([Callback]): the Call button answers. */
        val ringing: Boolean = false,
    ) {
        val live get() = state != null && state !is CallState.Ended
        /** The mute button shows Unmute: muted by a tap or by voice. */
        val micOff get() = muted || voiceMuted
        /** The pause button shows while there's something to pause, or while paused. */
        val pausable get() = live && (paused || state is CallState.Speaking || state is CallState.Thinking)
    }

    private val _ui = MutableStateFlow(Ui())
    val ui: StateFlow<Ui> = _ui

    internal fun update(context: Context, f: (Ui) -> Ui) {
        val before = _ui.value.live
        _ui.update(f)
        if (_ui.value.live != before) {
            TileService.requestListeningState(context, ComponentName(context, JarvisTile::class.java))
        }
    }

    /** Starts a call (the mic permission must be granted already), or continues the dropped call
     *  `rejoin`. Does nothing while one is up. */
    fun start(context: Context, rejoin: String? = null, task: String? = null) {
        if (_ui.value.live) {
            if (task != null) android.widget.Toast.makeText(context, "You're on a call: just say the task's name", android.widget.Toast.LENGTH_LONG).show()
            return
        }
        update(context) { Ui(state = CallState.Connecting) }
        context.startForegroundService(Intent(context, CallService::class.java).putExtra(CallService.REJOIN, rejoin).putExtra(CallService.TASK, task))
    }

    /**
     * The process died in a call (an update, the system): the app's next start rejoins it at once,
     * Aviv doing nothing. Where Android won't let a call start from the background, Jarvis rings
     * instead ([Callback]).
     */
    fun resume(context: Context) {
        if (_ui.value.live) return
        val id = CallStore.active(context) ?: return
        if (!CallService.micGranted(context)) return
        android.util.Log.i("TetherCall", "resuming call $id after a restart")
        update(context) { Ui(state = CallState.Reconnecting) }
        runCatching {
            context.startForegroundService(
                Intent(context, CallService::class.java).putExtra(CallService.REJOIN, id).putExtra(CallService.AUTO, true),
            )
        }.onFailure {
            android.util.Log.w("TetherCall", "can't resume from the background; ringing", it)
            update(context) { Ui() }
            CallService.ringBack(context, id)
        }
    }

    /** When the speaker last played a sound, and Aviv's voice last reached the mic (elapsedRealtime). */
    @Volatile internal var soundAt = 0L
    @Volatile internal var voiceAt = 0L

    /**
     * A pause an app update may fall in (its restart drops the line for a moment): no call, or Jarvis
     * listening with nothing left to play and Aviv quiet for [QUIET_MS].
     */
    fun quiet(): Boolean {
        val ui = _ui.value
        if (!ui.live) return true
        if (ui.state !is CallState.Listening && ui.state !is CallState.Reconnecting) return false
        val now = android.os.SystemClock.elapsedRealtime()
        return now - soundAt >= QUIET_MS && now - voiceAt >= QUIET_MS
    }

    private const val QUIET_MS = 1500L

    fun hangup(context: Context) = command(context, CallService.HANGUP)

    fun toggleMute(context: Context) = command(context, CallService.MUTE)

    fun nextRoute(context: Context) = command(context, CallService.ROUTE)

    fun togglePause(context: Context) = command(context, CallService.PAUSE)

    private fun command(context: Context, action: String) {
        if (!_ui.value.live) return
        context.startService(Intent(context, CallService::class.java).setAction(action))
    }

    /** A call's length as a phone shows it: 4:07, then 1:04:07. */
    fun clock(ms: Long): String {
        val t = (ms / 1000).coerceAtLeast(0)
        val (h, m, s) = Triple(t / 3600, t / 60 % 60, t % 60)
        return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
    }

    /** The call's status line: "Paused" over the state it paused in. */
    fun label(ui: Ui): String = if (ui.live && ui.paused) "Paused" else label(ui.state)

    /** The live call's line on the screen and in the notification, with how the mic is muted. */
    fun line(ui: Ui): String = when {
        ui.muted -> label(ui) + " · muted"
        ui.voiceMuted && ui.state !is CallState.Reconnecting -> "Muted by voice · say \"unmute\""
        else -> label(ui)
    }

    fun label(s: CallState?): String = when (s) {
        null -> "Call Jarvis"
        CallState.Connecting -> "Calling…"
        CallState.Listening -> "Listening"
        CallState.Thinking -> "Thinking"
        CallState.Speaking -> "Speaking"
        CallState.Reconnecting -> "Reconnecting…"
        is CallState.Ended -> "Call ended: ${s.why}"
    }
}
