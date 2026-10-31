package com.kivan.jarvis.tabs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GridTest {
    private val w = 900f
    private val h = 1800f
    private val gap = 16f

    @Test
    fun everyCountFillsTheWallWithNoHole() {
        for (n in 1..40) {
            val g = Grid.of(n, w, h, gap)
            assertEquals(n, g.rects.size)
            val rows = g.rects.groupBy { it.top }
            for ((_, row) in rows) {
                assertEquals("left edge, $n tiles", 0f, row.first().left, 0.01f)
                assertEquals("right edge, $n tiles", w, row.last().right, 0.01f)
            }
            assertEquals("bottom edge, $n tiles", h, g.rects.last().bottom, 0.01f)
        }
    }

    @Test
    fun tilesNeverOverlap() {
        for (n in 2..40) {
            val r = Grid.of(n, w, h, gap).rects
            for (i in r.indices) for (j in i + 1 until r.size) {
                assertTrue("$n tiles: $i and $j", !r[i].overlaps(r[j]) || r[i].intersect(r[j]).width <= 0f)
            }
        }
    }

    @Test
    fun aTileKeepsItsOrderAcrossTheWall() {
        val r = Grid.of(14, w, h, gap).rects
        for (i in 1 until r.size) {
            assertTrue(r[i].top > r[i - 1].top || r[i].left > r[i - 1].left)
        }
    }

    @Test
    fun aPhoneWallStaysReadableAt14() {
        val g = Grid.of(14, 400f, 750f, 8f)
        assertTrue("columns ${g.columns}", g.columns in 2..3)
        assertTrue("tile height ${g.rects[0].height}", g.rects[0].height > 90f)
    }

    @Test
    fun nothingOpenOrNoRoomMeansNoTiles() {
        assertTrue(Grid.of(0, w, h, gap).rects.isEmpty())
        assertTrue(Grid.of(3, 0f, h, gap).rects.isEmpty())
    }
}
