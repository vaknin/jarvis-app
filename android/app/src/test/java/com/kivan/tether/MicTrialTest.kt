package com.kivan.tether

import com.kivan.tether.MicTrial.Way
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MicTrialTest {
    /** 2 s of speech at this peak, then a pause: whether the mic reopened, and only in the pause. */
    private fun MicTrial.speech(peak: Int): Boolean {
        repeat(100) { assertFalse(frame(peak) {}) }
        return (1..30).map { frame(20) {} }.any { it }
    }

    @Test
    fun aMicAtTheUsualLevelNeverReopens() {
        val t = MicTrial()
        assertFalse(t.speech(3000))
        assertTrue(t.done)
        assertFalse(t.speech(100))
        assertEquals(Way.USUAL, t.way)
    }

    @Test
    fun hissMovesNothing() {
        val t = MicTrial()
        repeat(10_000) { assertFalse(t.frame(60) {}) }
        assertEquals(Way.USUAL, t.way)
        assertFalse(t.done)
    }

    @Test
    fun aQuietMicTriesEachWayAndGoesBackIfNoneHelps() {
        val t = MicTrial()
        assertTrue(t.speech(300))
        assertEquals(Way.NO_EFFECTS, t.way)
        assertTrue(t.speech(320))
        assertEquals(Way.RECOGNITION, t.way)
        assertTrue(t.speech(310))
        assertEquals(Way.PLAIN, t.way)
        assertTrue(t.speech(300))
        assertEquals(Way.USUAL, t.way)
        assertTrue(t.done)
    }

    @Test
    fun aWayThatBringsHimBackIsKept() {
        val t = MicTrial()
        assertTrue(t.speech(300))
        assertFalse(t.speech(3000))
        assertEquals(Way.NO_EFFECTS, t.way)
        assertTrue(t.done)
    }

    @Test
    fun aClearlyBetterWayIsKeptEvenIfStillQuiet() {
        val t = MicTrial()
        t.speech(100)
        t.speech(120)
        t.speech(500)
        assertTrue(t.speech(110))
        assertEquals(Way.RECOGNITION, t.way)
    }

    @Test
    fun aWayTheSystemRefusesIsSkipped() {
        val t = MicTrial()
        t.speech(300)
        t.refused {}
        assertEquals(Way.RECOGNITION, t.way)
        assertFalse(t.done)
    }

    @Test
    fun aKeptWayThatFailsLaterGoesBackToTheUsualOne() {
        val t = MicTrial()
        t.speech(300)
        t.speech(3000)
        t.refused {}
        assertEquals(Way.USUAL, t.way)
    }

    @Test
    fun breathAndNoiseDontHideALoudMic() {
        val t = MicTrial()
        repeat(100) { i -> assertFalse(t.frame(if (i % 5 == 0) 3000 else 100) {}) }
        assertTrue(t.done)
        assertEquals(Way.USUAL, t.way)
    }

    @Test
    fun theReopenWaitsForAGapInHisSpeech() {
        val t = MicTrial()
        repeat(100) { t.frame(300) {} }
        repeat(50) { assertFalse(t.frame(300) {}) }
        repeat(14) { assertFalse(t.frame(20) {}) }
        assertTrue(t.frame(20) {})
    }

    @Test
    fun aTrialThatMovedReportsOnceAndAFineMicNever() {
        val fine = MicTrial()
        fine.speech(3000)
        assertFalse(fine.report())
        val t = MicTrial()
        t.speech(300)
        assertFalse(t.report())
        t.speech(3000)
        assertTrue(t.report())
        assertFalse(t.report())
    }
}
