package com.kivan.jarvis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MicLevelTest {
    @Test
    fun itRisesFastAndFallsSlowly() {
        assertTrue(MicLevel.ease(0f, 1f, 60f) > 0.8f)
        assertTrue(MicLevel.ease(1f, 0f, 60f) > 0.7f)
    }

    @Test
    fun itEasesTheSameAtAnyFrameRate() {
        val at60 = (1..6).fold(1f) { s, _ -> MicLevel.ease(s, 0f, 100f / 6) }
        val at120 = (1..12).fold(1f) { s, _ -> MicLevel.ease(s, 0f, 100f / 12) }
        assertEquals(at60, at120, 0.001f)
    }

    @Test
    fun itFallsAllTheWayToZero() = assertEquals(0f, MicLevel.ease(0.5f, 0f, 2000f))
}
