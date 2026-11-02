package com.kivan.jarvis

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AirPodsMicTest {
    @Test
    fun leftAndRightPinTheirBudAndNeverMove() {
        val l = BudChoice(AirPodsMic.Mode.LEFT, Bud.RIGHT)
        assertEquals(Bud.LEFT, l.start())
        assertNull(l.quiet())
        assertEquals(Bud.LEFT, l.fine())
        assertEquals(Bud.RIGHT, BudChoice(AirPodsMic.Mode.RIGHT, null).start())
    }

    @Test
    fun fixTriesEachBudOnceFromTheBudsOwnChoice() {
        val b = BudChoice(AirPodsMic.Mode.FIX, null)
        assertNull("the buds choose at first", b.start())
        assertNull("nothing to remember while they choose", b.fine())
        assertEquals(Bud.RIGHT, b.quiet())
        assertEquals(Bud.LEFT, b.quiet())
        assertNull("both tried", b.quiet())
        assertEquals(Bud.LEFT, b.fine())
    }

    @Test
    fun fixStartsOnTheBudThatWorkedLastTime() {
        val b = BudChoice(AirPodsMic.Mode.FIX, Bud.LEFT)
        assertEquals(Bud.LEFT, b.start())
        assertEquals(Bud.RIGHT, b.quiet())
        assertNull(b.quiet())
    }

    @Test
    fun plainAndOffSendNothing() {
        for (m in listOf(AirPodsMic.Mode.PLAIN, AirPodsMic.Mode.OFF)) {
            val b = BudChoice(m, Bud.LEFT)
            assertNull(b.start())
            assertNull(b.quiet())
        }
        assertEquals(false, AirPodsMic.Mode.OFF.budsMic)
    }

    @Test
    fun micModeBytesAreLibrePods() {
        val h = byteArrayOf(0x04, 0x00, 0x04, 0x00, 0x09, 0x00, 0x01)
        assertArrayEquals(h + byteArrayOf(0x02, 0, 0, 0), AapPackets.control(AapPackets.MIC_MODE, AapPackets.micMode(Bud.LEFT)))
        assertArrayEquals(h + byteArrayOf(0x01, 0, 0, 0), AapPackets.control(AapPackets.MIC_MODE, AapPackets.micMode(Bud.RIGHT)))
        assertArrayEquals(h + byteArrayOf(0x00, 0, 0, 0), AapPackets.control(AapPackets.MIC_MODE, AapPackets.micMode(null)))
    }

    @Test
    fun twoPacketsInOneReadAreSplitAndRead() {
        val a = AapPackets.control(AapPackets.MIC_MODE, 2)
        val b = AapPackets.control(0x0D, 2)
        val said = AapPackets.split(a + b).map { AapPackets.parse(it) as AapPackets.Said.Control }.map(AapPackets::describe)
        assertEquals(listOf("mic mode left", "listening mode noise cancelling"), said)
    }
}
