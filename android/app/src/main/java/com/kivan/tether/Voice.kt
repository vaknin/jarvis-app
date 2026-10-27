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
        /** The newest line heard from Aviv and said by Jarvis. */
        val heard: String = "",
        val said: String = "",
        val error: String? = null,
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

    private fun command(context: Context, action: String) {
        if (!_ui.value.live) return
        context.startService(Intent(context, CallService::class.java).setAction(action))
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
