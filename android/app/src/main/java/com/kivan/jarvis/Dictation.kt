package com.kivan.jarvis

import android.content.Context
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import com.kivan.jarvis.core.CallState
import com.kivan.jarvis.core.MicPick
import com.kivan.jarvis.core.Route
import com.kivan.jarvis.core.Sensed
import com.kivan.jarvis.core.VoiceCall
import com.kivan.jarvis.core.VoiceListener
import com.kivan.jarvis.core.micNote
import com.kivan.jarvis.core.pickRoute
import com.kivan.jarvis.tabs.RealDeck
import com.kivan.jarvis.tabs.WALL
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/**
 * The mic button in a words box: he speaks, the words collect, Jarvis writes. No sound, no call route,
 * no notification, no audio focus, so music plays on at full quality. It only runs while Tabs is on
 * screen, so it isn't a service: leaving the app stops it and what he said stays as a draft.
 */
object Dictation {
    private const val TAG = "JarvisDictation"
    private const val TRIES = 3
    private const val CLOSE_WAIT_MS = 10_000L

    val on = MutableStateFlow(false)

    /** The line to the server, still up while his last words land. */
    @Volatile var call: VoiceCall? = null
        private set

    private var senses: Senses? = null
    private var scope: CoroutineScope? = null
    private var after: (() -> Unit)? = null
    private var app: Context? = null
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var route = Route(MicPick.PHONE, false, false, "")
    @Volatile private var running = false
    @Volatile private var reroute = false

    fun toggle(context: Context) = if (on.value) stop() else start(context.applicationContext)

    fun start(app: Context) {
        if (on.value) return
        if (call != null) {
            // The last one is still closing: this one follows it.
            then { start(app) }
            return
        }
        if (Move.off() || !CallService.micGranted(app)) return
        val listener = Listener(app)
        val c = VoiceCall.dictate(BuildConfig.VOICE_URL, listener, Voice.place.value.tab ?: Voice.NEW_TAB)
        listener.line = c
        this.app = app
        runCatching { Forum.start() }
        call = c
        on.value = true
        running = true
        val s = Senses(app, call = false) { sensed(it) }
        senses = s
        route = pickRoute(s.sensed())
        micNote("rule: ${route.rule}: mic ${route.mic}")
        s.start()
        scope = CoroutineScope(Dispatchers.Default + SupervisorJob()).also { sc ->
            sc.launch { Voice.place.collect { c.setWhere(it.tab, false, it.stay) } }
        }
        Thread({ mic(app, c) }, "jarvis-dictation").apply { priority = Thread.MAX_PRIORITY }.start()
    }

    /** Stops listening; `then` runs when the line is closed, so nothing it starts meets this one. */
    fun stop(then: () -> Unit = {}) {
        val c = call
        if (c == null) {
            then()
            return
        }
        then(then)
        if (on.value) {
            on.value = false
            running = false
            senses?.stop()
            c.hangup()
        }
        // A line that never says it closed can't hold up what's next.
        main.postDelayed({ app?.let { closed(it, c) } }, CLOSE_WAIT_MS)
    }

    private fun then(next: () -> Unit) {
        val was = after
        after = { was?.invoke(); next() }
    }

    private fun sensed(s: Sensed) {
        val r = pickRoute(s)
        if (r == route) return
        route = r
        micNote("rule: ${r.rule}: mic ${r.mic}")
        reroute = true
    }

    private fun mic(app: Context, c: VoiceCall) {
        val am = app.getSystemService(AudioManager::class.java)
        val plain = PlainMic(am, { route.rule }, { !running }, { reroute }, c::sendAudio)
        var failed = 0
        while (running) {
            reroute = false
            val end = if (route.mic == MicPick.LARK) plain.lark() else plain.phone()
            if (end != MicEnd.FAILED) failed = 0
            else if (++failed >= TRIES) {
                Log.w(TAG, "the mic stopped")
                main.post {
                    Toast.makeText(app, "The microphone stopped", Toast.LENGTH_LONG).show()
                    stop()
                }
                return
            }
        }
    }

    /** The server closed the line, or never will: what he said is a draft now. */
    private fun closed(app: Context, c: VoiceCall) {
        if (call !== c) return
        call = null
        on.value = false
        running = false
        senses?.stop()
        senses = null
        scope?.cancel()
        scope = null
        val words = Voice.heard.value
        Voice.heard.value = ""
        if (words.isNotBlank()) RealDeck.get(app).kept(Voice.place.value.tab ?: WALL, words)
        val next = after
        after = null
        next?.invoke()
    }

    private class Listener(private val app: Context) : VoiceListener {
        @Volatile var line: VoiceCall? = null

        override fun onState(state: CallState) {
            if (state is CallState.Ended) main.post { line?.let { closed(app, it) } }
        }
        override fun onWords(text: String) {
            Voice.heard.value = text
        }
        override fun onTab(id: String, n: UInt) = Voice.moved(id, n.toInt())
        override fun onError(text: String) {
            Log.w(TAG, text)
        }
        override fun onLine(text: String) {}
        override fun onJoined(id: String) {}
        override fun onHangupOwed(id: String) {}
        override fun onText(who: String, text: String) {}
        override fun onFlush() {}
        override fun onPaused(on: Boolean) {}
        override fun onVoiceMuted(on: Boolean) {}
    }
}
