package com.kivan.jarvis.tabs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Task 1266: a tap on a message's ticks shows what they stand for; the server is asked once, and never again once it answered. */
class TicksTest {
    private val s0 = DeckState()

    @Test fun firstTapOpensAndAsksSecondHidesThirdShowsWithoutAsking() {
        val (s1, ask1) = s0.tapTick("t0", 30)
        assertTrue(ask1)
        assertEquals("…", s1.ticks.getValue(tickKey("t0", 30)).shown())
        val s2 = s1.ticked("t0", 30, "Filed.", "")
        assertEquals("Filed.", s2.ticks.getValue(tickKey("t0", 30)).shown())
        val (s3, ask2) = s2.tapTick("t0", 30)
        assertFalse(ask2)
        assertFalse(s3.ticks.getValue(tickKey("t0", 30)).open)
        val (s4, ask3) = s3.tapTick("t0", 30)
        assertFalse("answered once, never asked again", ask3)
        assertEquals(TickWords(open = true, text = "Filed."), s4.ticks.getValue(tickKey("t0", 30)))
    }

    @Test fun aTapWhileTheAskIsOnItsWayNeverAsksAgain() {
        val (s1, _) = s0.tapTick("t0", 30)
        val (s2, hide) = s1.tapTick("t0", 30)
        val (s3, show) = s2.tapTick("t0", 30)
        assertFalse(hide || show)
        assertEquals("…", s3.ticks.getValue(tickKey("t0", 30)).shown())
        // The answer fills it even after it was hidden and shown again.
        assertEquals("Filed.", s3.ticked("t0", 30, "Filed.", "").ticks.getValue(tickKey("t0", 30)).shown())
    }

    @Test fun noWordsKeptSaysSoAndIsAnAnswer() {
        val s = s0.tapTick("t0", 50).first.ticked("t0", 50, "", "")
        assertEquals(NO_TICK_WORDS, s.ticks.getValue(tickKey("t0", 50)).shown())
        val again = s.tapTick("t0", 50).first.tapTick("t0", 50)
        assertFalse(again.second)
    }

    @Test fun aFailedAskSaysWhyAndTheNextOpeningAsksAgain() {
        val s = s0.tapTick("t0", 30).first.ticked("t0", 30, "", "no answer in 30 s")
        assertEquals("Couldn't get them: no answer in 30 s", s.ticks.getValue(tickKey("t0", 30)).shown())
        val hidden = s.tapTick("t0", 30)
        assertFalse(hidden.second)
        val (open, ask) = hidden.first.tapTick("t0", 30)
        assertTrue(ask)
        assertEquals("…", open.ticks.getValue(tickKey("t0", 30)).shown())
    }

    @Test fun eachMessageHasItsOwn() {
        val s = s0.tapTick("t0", 30).first.ticked("t0", 30, "Filed.", "")
        assertTrue(s.tapTick("t0", 50).second)
        assertTrue(s.tapTick("t1", 30).second)
    }
}
