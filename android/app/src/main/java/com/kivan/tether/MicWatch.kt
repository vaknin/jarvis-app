package com.kivan.tether

/**
 * When a headset's mic gets reopened. The AirPods' mic can open in a state where speech arrives 20 to 40 dB down and
 * only climbs while he keeps talking, under the server's speech gate, so a word is lost and a long sentence gets
 * through. On 2026-10-30 one call went from peaks of 85 to 1700 to 3300 to 19000, for good, the moment its mic was
 * closed and opened again in a pause; no other change on that call. So a stretch on a headset reopens once, in its first
 * pause, whatever the level (a mic gap in a pause costs nothing and his first sentence is worth it), and again if voice
 * ever comes through quiet. Only the mic thread uses it.
 */
class MicWatch {
    private val peaks = IntArray(FRAMES)
    private var filled = 0
    /** Frames since the window's first one. */
    private var span = 0
    private var age = 0
    private var gap = 0
    private var left = QUIET_REOPENS
    private var saidSo = false
    /** A reopen is waiting for a pause, so it doesn't eat his words. */
    private var due = true

    /** One frame's peak; [listening]: Jarvis is quiet and the mic is live, so what's there is Aviv. True: reopen now. */
    fun frame(peak: Int, listening: Boolean, log: (String) -> Unit): Boolean {
        age++
        if (due) {
            gap = if (peak < VOICE) gap + 1 else 0
            if (age < SETTLE_FRAMES || gap < GAP) return false
            due = false
            age = 0
            gap = 0
            filled = 0
            span = 0
            return true
        }
        if (!listening) return false
        if (filled > 0 && ++span > SPAN) {
            // Scattered breaths and rustles, not speech.
            filled = 0
            span = 0
        }
        if (peak < VOICE) return false
        peaks[filled] = peak
        if (++filled < FRAMES) return false
        filled = 0
        span = 0
        // The loud end of it: a mean drowns in breath and room noise.
        val level = peaks.sorted()[FRAMES * 9 / 10]
        if (level >= FINE) {
            left = QUIET_REOPENS
            return false
        }
        if (left > 0) {
            left--
            log("voice at peak $level: reopening in the next pause")
            due = true
        } else if (!saidSo) {
            saidSo = true
            log("voice at peak $level, and reopening didn't help: the server may not hear him")
        }
        return false
    }

    private companion object {
        /** A frame this loud may be him: the hiss sits at 8 to 24, the quietest speech on record at 30 to 214. */
        const val VOICE = 30
        /** 1 s of voice is enough to judge. */
        const val FRAMES = 50
        /** ...heard within 5 s. */
        const val SPAN = 250
        /** The loudest tenth of a second of speech on normal AirPods calls starts at 2000 to 5000, on the quiet ones at about 300. */
        const val FINE = 1500
        /** 300 ms under [VOICE] is a pause. */
        const val GAP = 15
        /** A mic this new is never reopened: the route gets a second to settle. */
        const val SETTLE_FRAMES = 50
        /**
         * For a mic that stays quiet: more than this doesn't help it. A window at the usual level gives them back, since
         * good AirPods calls have a soft one now and then (2% of windows, never two in a row) and a long call can still relapse.
         */
        const val QUIET_REOPENS = 2
    }
}
