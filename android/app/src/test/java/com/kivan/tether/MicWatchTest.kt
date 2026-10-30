package com.kivan.tether

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MicWatchTest {
    private fun MicWatch.feed(peak: Int, frames: Int, listening: Boolean = true) = (1..frames).count { frame(peak, listening) {} }

    /** The reopen every stretch on a headset starts with. */
    private fun opened() = MicWatch().also { assertEquals(1, it.feed(20, 66)) }

    @Test
    fun aHeadsetReopensOnceInItsFirstPause() {
        val w = MicWatch()
        assertEquals("not in the first second", 0, w.feed(20, 40))
        assertEquals("not over his words", 0, w.feed(3000, 200))
        assertEquals(1, w.feed(20, 16))
        assertEquals(0, w.feed(20, 5000))
    }

    @Test
    fun itWaitsForAPauseWhileJarvisTalksToo() {
        val w = MicWatch()
        assertEquals(1, w.feed(20, 100, listening = false))
    }

    @Test
    fun aMicAtTheUsualLevelIsLeftAlone() {
        val w = opened()
        repeat(20) { assertEquals(0, w.feed(3000, 100) + w.feed(20, 50)) }
    }

    @Test
    fun aQuietMicReopensInTheNextPauseOnly() {
        val w = opened()
        assertEquals("not over his words", 0, w.feed(300, 200))
        assertEquals(1, w.feed(20, 16))
    }

    @Test
    fun aMicThatStaysQuietStopsBeingReopened() {
        val w = opened()
        repeat(2) {
            assertEquals(0, w.feed(300, 100))
            assertEquals(1, w.feed(20, 16))
        }
        repeat(20) { assertEquals(0, w.feed(300, 100) + w.feed(20, 50)) }
    }

    @Test
    fun softMomentsOnAGoodMicNeverUseUpItsReopens() {
        val w = opened()
        repeat(10) {
            assertEquals(0, w.feed(300, 50))
            assertEquals(1, w.feed(20, 16))
            assertEquals(0, w.feed(3000, 50))
        }
    }

    @Test
    fun hissMovesNothing() {
        val w = opened()
        assertEquals(0, w.feed(24, 50_000))
    }

    @Test
    fun scatteredRustlesNeverAddUpToVoice() {
        val w = opened()
        // A minute of a rustle every 2 s: hundreds of loud frames, but never 1 s of them inside 5 s.
        repeat(30) { assertEquals(0, w.feed(200, 10) + w.feed(20, 90)) }
    }

    @Test
    fun jarvisInTheBudsIsNotHisVoice() {
        val w = opened()
        assertEquals(0, w.feed(300, 500, listening = false) + w.feed(20, 50, listening = false))
    }

    @Test
    fun theCallsLogSaysWhenReopeningDidntHelp() {
        val w = opened()
        repeat(2) {
            w.feed(300, 100)
            w.feed(20, 16)
        }
        val said = mutableListOf<String>()
        repeat(500) { w.frame(300, true) { said += it } }
        assertTrue(said.single(), said.single().contains("didn't help"))
    }

    @Test
    fun theQuietReopenSaysWhy() {
        val w = opened()
        val said = mutableListOf<String>()
        repeat(50) { w.frame(300, true) { said += it } }
        assertTrue(said.single(), said.single().contains("peak 300"))
        assertFalse(w.frame(300, true) {})
    }
}
