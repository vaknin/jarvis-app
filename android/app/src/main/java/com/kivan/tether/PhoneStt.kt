package com.kivan.tether

import android.content.Context
import android.content.Intent
import android.media.AudioFormat
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.speech.RecognitionListener
import android.speech.RecognitionPart
import android.speech.RecognitionSupport
import android.speech.RecognitionSupportCallback
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import com.kivan.tether.core.SttWord
import com.kivan.tether.core.VoiceCall
import java.io.FileOutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicInteger

/**
 * The Pixel's on-device speech recognizer hearing Aviv's turns on a call (thread #118), instead of
 * the server's Parakeet. The books are Rust's (crates/voice `stt.rs`): it opens a session, feeds it
 * every mic frame the socket carried, closes it at each turn's end (the server's `cut`), and turns
 * the words into `heard`. Here each session is one recognizer, fed through a pipe (EXTRA_AUDIO_SOURCE,
 * so the call's own AudioRecord stays the only one), segmented so it runs until EOF, with word
 * timing (each word's start from the session's first sample) and formatting (endpoint.rs needs `?`).
 *
 * One recognizer at a time: the next session waits for the last one's final (0.4-1.6 s after EOF),
 * queuing its audio, and the recognizer catches up faster than real time. A recognizer still busy
 * with the last one says ERROR_RECOGNIZER_BUSY: it is asked again shortly.
 * Every session ends in [VoiceCall.sttDone], with its words or null; the server falls back to
 * Parakeet's words 2 s after the turn's end anyway.
 */
class PhoneStt(private val context: Context) {
    /** Set right after the call is made: the listener can't reach it before it exists. */
    @Volatile var call: VoiceCall? = null

    private val main = Handler(Looper.getMainLooper())

    private inner class Session(val id: Long) {
        val queue = LinkedBlockingQueue<ByteArray>()
        val queued = AtomicInteger()
        var recognizer: SpeechRecognizer? = null
        var pipe: Array<ParcelFileDescriptor>? = null
        var writer: Thread? = null
        val words = ArrayList<SttWord>()
        var tries = 0
        var over = false
        @Volatile var closedAt = 0L
        var startedAt = 0L
        val openedAt = System.nanoTime()
    }

    /** Main thread only, oldest first. */
    private val sessions = LinkedHashMap<Long, Session>()
    /** The same sessions, for the runtime thread that feeds them. */
    private val feeds = ConcurrentHashMap<Long, Session>()
    private var stopped = false

    fun open(id: Long) {
        val s = Session(id)
        feeds[id] = s
        main.post {
            if (stopped) return@post
            sessions[id] = s
            pump()
        }
    }

    /** Never blocks: called on the call's runtime thread. */
    fun audio(id: Long, pcm: ByteArray) {
        val s = feeds[id] ?: return
        // A recognizer that never starts doesn't keep a whole call in memory.
        if (s.queued.addAndGet(pcm.size) > MAX_QUEUED) {
            feeds.remove(id)
            fail(s, "over ${MAX_QUEUED / 32000} s queued")
            return
        }
        s.queue.offer(pcm)
    }

    fun close(id: Long) {
        val s = feeds[id] ?: return
        s.closedAt = System.nanoTime()
        s.queue.offer(EOF)
        main.post { watch(s) }
    }

    /** A closed session whose recognizer hasn't answered [FINAL_WAIT_MS] after its end (or after it
     *  started, if it waited for the one before) is given up, so the next one isn't held behind it. */
    private fun watch(s: Session) {
        if (s.over || stopped) return
        val since = maxOf(s.closedAt, s.startedAt)
        if (s.recognizer != null && System.nanoTime() - since > FINAL_WAIT_MS * 1_000_000) {
            return end(s, null, "no final ${FINAL_WAIT_MS} ms after its end")
        }
        main.postDelayed({ watch(s) }, 500)
    }

    /** The call is over: every recognizer goes, nothing more is answered. */
    fun stop() = main.post {
        stopped = true
        for (s in sessions.values.toList()) drop(s)
        sessions.clear()
        feeds.clear()
    }

    private fun fail(s: Session, why: String) = main.post { if (!s.over) end(s, null, why) }

    /** Starts the oldest session's recognizer, once the one before it has finished. */
    private fun pump() {
        if (stopped) return
        val s = sessions.values.firstOrNull() ?: return
        if (s.recognizer == null) start(s)
    }

    private fun start(s: Session) {
        val r = try {
            SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
        } catch (e: Exception) {
            return end(s, null, "no recognizer: $e")
        }
        val pipe = ParcelFileDescriptor.createPipe()
        s.recognizer = r
        s.pipe = pipe
        s.startedAt = System.nanoTime()
        r.setRecognitionListener(Listener(s, r))
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, LANG)
            .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, pipe[0])
            .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT, 1)
            .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
            .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE, 16000)
            .putExtra(RecognizerIntent.EXTRA_SEGMENTED_SESSION, RecognizerIntent.EXTRA_AUDIO_SOURCE)
            .putExtra(RecognizerIntent.EXTRA_MASK_OFFENSIVE_WORDS, false)
            .putExtra(RecognizerIntent.EXTRA_REQUEST_WORD_TIMING, true)
            .putExtra(RecognizerIntent.EXTRA_ENABLE_FORMATTING, RecognizerIntent.FORMATTING_OPTIMIZE_QUALITY)
        try {
            r.startListening(i)
        } catch (e: Exception) {
            end(s, null, "start: $e")
        }
    }

    /** Writes the session's audio into its pipe, once its recognizer is ready, until EOF. */
    private fun write(s: Session, pipe: ParcelFileDescriptor) {
        try {
            FileOutputStream(pipe.fileDescriptor).use { out ->
                while (true) {
                    val b = s.queue.take()
                    if (b === EOF) break
                    s.queued.addAndGet(-b.size)
                    out.write(b)
                }
            }
        } catch (_: InterruptedException) {
        } catch (e: Exception) {
            // The recognizer let go of its end: it has stopped listening.
            if (!s.over) fail(s, "writer: $e")
        } finally {
            runCatching { pipe.close() }
        }
    }

    private inner class Listener(val s: Session, val r: SpeechRecognizer) : RecognitionListener {
        private fun mine() = !s.over && s.recognizer === r

        override fun onReadyForSpeech(p: Bundle?) {
            if (!mine() || s.writer != null) return
            val w = s.pipe?.get(1) ?: return
            s.writer = Thread({ write(s, w) }, "jarvis-stt-${s.id}").apply { start() }
        }

        override fun onSegmentResults(b: Bundle) {
            if (mine()) s.words += parts(b)
        }

        override fun onEndOfSegmentedSession() {
            if (mine()) end(s, s.words, null)
        }

        override fun onResults(b: Bundle?) {
            if (!mine()) return
            if (s.words.isEmpty() && b != null) s.words += parts(b)
            end(s, s.words, null)
        }

        override fun onError(e: Int) {
            if (!mine()) return
            when {
                // The last session's recognizer is still on its way out: ask again shortly.
                e == SpeechRecognizer.ERROR_RECOGNIZER_BUSY && s.writer == null && ++s.tries < BUSY_TRIES -> {
                    release(s)
                    main.postDelayed({ if (!s.over && !stopped) start(s) }, BUSY_WAIT_MS)
                }
                // Heard no words (silence, noise): that is an answer.
                e == SpeechRecognizer.ERROR_NO_MATCH || e == SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> end(s, s.words, "error $e")
                else -> end(s, null, "error $e")
            }
        }

        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(v: Float) {}
        override fun onBufferReceived(b: ByteArray?) {}
        override fun onEndOfSpeech() {}
        override fun onPartialResults(b: Bundle?) {}
        override fun onEvent(t: Int, p: Bundle?) {}
    }

    /** The session is over: Rust hears its words (or null), and the next one may start. */
    private fun end(s: Session, words: List<SttWord>?, why: String?) {
        if (s.over) return
        val after = if (s.closedAt > 0) "${(System.nanoTime() - s.closedAt) / 1_000_000} ms after its end" else "while open"
        Log.i(TAG, "session ${s.id}: ${words?.size ?: "no"} words $after, ${(System.nanoTime() - s.openedAt) / 1_000_000} ms long${why?.let { " ($it)" } ?: ""}")
        drop(s)
        runCatching { call?.sttDone(s.id.toULong(), words?.toList()) }
        main.postDelayed(::pump, REST_MS)
    }

    private fun drop(s: Session) {
        s.over = true
        feeds.remove(s.id)
        sessions.remove(s.id)
        s.queue.clear()
        s.queue.offer(EOF)
        release(s)
    }

    private fun release(s: Session) {
        s.writer?.interrupt()
        s.writer = null
        s.recognizer?.let { runCatching { it.destroy() } }
        s.recognizer = null
        s.pipe?.forEach { runCatching { it.close() } }
        s.pipe = null
    }

    private fun parts(b: Bundle): List<SttWord> =
        b.getParcelableArrayList(SpeechRecognizer.RECOGNITION_PARTS, RecognitionPart::class.java).orEmpty()
            .map { SttWord((it.formattedText ?: it.rawText).trim(), it.timestampMillis.coerceAtLeast(0).toULong()) }
            .filter { it.text.isNotEmpty() }

    companion object {
        private const val TAG = "TetherStt"
        private const val LANG = "en-US"
        private val EOF = ByteArray(0)
        private const val MAX_QUEUED = 60 * 32000
        private const val FINAL_WAIT_MS = 6000L
        private const val BUSY_TRIES = 40
        private const val BUSY_WAIT_MS = 250L
        private const val REST_MS = 100L
        private const val PREFS = "phone-stt"

        @Volatile private var ready: Boolean? = null

        /** Whether calls use the phone's recognizer: on-device en-US installed, as last checked. */
        fun available(context: Context): Boolean =
            ready ?: context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("ready", false)

        /** Asks the system whether on-device en-US is installed (at app start and after each call). */
        fun check(context: Context) {
            val app = context.applicationContext
            Handler(Looper.getMainLooper()).post {
                fun keep(ok: Boolean, why: String) {
                    if (ready != ok) Log.i(TAG, "on-device speech to text: $ok ($why)")
                    ready = ok
                    app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("ready", ok).apply()
                }
                if (!SpeechRecognizer.isOnDeviceRecognitionAvailable(app)) return@post keep(false, "no on-device recognizer")
                val r = try {
                    SpeechRecognizer.createOnDeviceSpeechRecognizer(app)
                } catch (e: Exception) {
                    return@post keep(false, "$e")
                }
                val main = Handler(Looper.getMainLooper())
                r.checkRecognitionSupport(
                    Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).putExtra(RecognizerIntent.EXTRA_LANGUAGE, LANG),
                    Executors.newSingleThreadExecutor(),
                    object : RecognitionSupportCallback {
                        override fun onSupportResult(s: RecognitionSupport) {
                            val ok = s.installedOnDeviceLanguages.any { it.equals(LANG, ignoreCase = true) }
                            keep(ok, "installed ${s.installedOnDeviceLanguages}")
                            main.post { runCatching { r.destroy() } }
                        }

                        override fun onError(e: Int) {
                            Log.w(TAG, "support check: error $e")
                            main.post { runCatching { r.destroy() } }
                        }
                    },
                )
            }
        }
    }
}
