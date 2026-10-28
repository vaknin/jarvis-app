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
        val muted: Boolean = false,
        val error: String? = null,
        /** Where the call sounds now, and every place it can (the route button cycles them). */
        val route: AudioRoute? = null,
        val routes: List<AudioRoute> = emptyList(),
        /** When the call first connected (wall clock, ms): the duration counts from it, through every turn and reconnect. */
        val connectedAt: Long? = null,
    ) {
        val live get() = state != null && state !is CallState.Ended
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

    /** Starts a call (the mic permission must be granted already). Does nothing while one is up. */
    fun start(context: Context) {
        if (_ui.value.live) return
        update(context) { Ui(state = CallState.Connecting) }
        context.startForegroundService(Intent(context, CallService::class.java))
    }

    fun hangup(context: Context) = command(context, CallService.HANGUP)

    fun toggleMute(context: Context) = command(context, CallService.MUTE)

    fun nextRoute(context: Context) = command(context, CallService.ROUTE)

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
