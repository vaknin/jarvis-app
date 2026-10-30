package com.kivan.tether

import android.media.MediaRecorder

/**
 * A headset's mic that comes through far too quiet gets tried other ways, once, on the call itself, with the level of
 * each going up in the call's log. Temporary (thread #125): nobody knows yet whether the AirPods' quiet calls depend on
 * how the mic is opened, and they can't be tested without Aviv talking into them. Only speech moves it on, so a mic at
 * the usual level never reopens. The trial always stays on the headset's own mic: once the log has answered, it goes.
 */
class MicTrial {
    /** How the mic opens. VOICE_COMMUNICATION with the effects is the usual way. */
    enum class Way(val source: Int, val effects: Boolean) {
        USUAL(MediaRecorder.AudioSource.VOICE_COMMUNICATION, true),
        NO_EFFECTS(MediaRecorder.AudioSource.VOICE_COMMUNICATION, false),
        RECOGNITION(MediaRecorder.AudioSource.VOICE_RECOGNITION, false),
        PLAIN(MediaRecorder.AudioSource.MIC, false),
    }

    var way = Way.USUAL
        private set
    var done = false
        private set
    /** It moved past the usual way, and its log hasn't gone up yet: see [report]. */
    private var unreported = false
    private val heard = IntArray(Way.entries.size)
    private val peaks = IntArray(FRAMES)
    private var frames = 0
    /** A reopen is due, in the next gap in his speech, so it doesn't eat his words. */
    private var due = false
    private var gap = 0

    /** One frame's peak while Aviv may be talking. True: reopen the mic as [way]. */
    fun frame(peak: Int, log: (String) -> Unit): Boolean {
        if (due) {
            gap = if (peak < SPEECH) gap + 1 else 0
            if (gap < GAP) return false
            due = false
            gap = 0
            return true
        }
        if (done || peak < SPEECH) return false
        peaks[frames] = peak
        if (++frames < FRAMES) return false
        frames = 0
        // The loud end of it: a mean drowns in breath and room noise.
        val level = peaks.sorted()[FRAMES * 9 / 10]
        heard[way.ordinal] = level
        log("trial: $way gave speech at peak $level")
        if (level >= FINE) {
            done = true
            return false
        }
        due = next(log)
        return false
    }

    /** True once, when a trial that moved has ended: its log is worth sending now. */
    fun report(): Boolean {
        if (!done || !unreported) return false
        unreported = false
        return true
    }

    /** The mic wouldn't open this way, or not on the headset: the next way, and the usual one for good once the trial is over. */
    fun refused(log: (String) -> Unit) {
        log("trial: $way refused")
        if (done) way = Way.USUAL else next(log)
    }

    private fun next(log: (String) -> Unit): Boolean {
        unreported = true
        Way.entries.getOrNull(way.ordinal + 1)?.let {
            way = it
            return true
        }
        done = true
        val best = Way.entries.maxBy { heard[it.ordinal] }
        val keep = if (heard[best.ordinal] >= heard[Way.USUAL.ordinal] * CLEARLY_BETTER) best else Way.USUAL
        log("trial: keeping $keep")
        val back = keep != way
        way = keep
        return back
    }

    private companion object {
        /** A frame this loud may be speech: the hiss peaks under it, the quietest call's speech at 30 to 214. */
        const val SPEECH = 80
        /** 2 s of it is enough to judge a way. */
        const val FRAMES = 100
        /** The loudest tenth of 2 s of speech on normal AirPods calls starts at 2000 to 5000, on the quiet ones at about 1000. */
        const val FINE = 1500
        /** 300 ms under [SPEECH] is a gap. */
        const val GAP = 15
        /** Anything else has to beat the usual way by this much (10 dB), or the phone's echo cancelling stays. */
        const val CLEARLY_BETTER = 3
    }
}
