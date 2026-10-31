package com.kivan.jarvis.tabs

import androidx.compose.ui.geometry.Rect
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.ln

enum class TileSize { Big, Large, Medium, Small }

/**
 * How [n] tiles fill a wall of [w] by [h]. The columns are picked by the shape of the tile they give,
 * so a phone and a tablet need no table: wider than tall reads best, for a name on one or two lines.
 * The last row stretches, so there is never a hole. Rects are in the wall's own units, gaps taken out.
 */
data class Grid(val columns: Int, val rects: List<Rect>) {
    companion object {
        /** A tile this wide for each one tall: the shape a name sits best in. */
        private const val SHAPE = 1.15f
        private const val MOST_COLUMNS = 6

        fun of(n: Int, w: Float, h: Float, gap: Float): Grid {
            if (n <= 0 || w <= 0f || h <= 0f) return Grid(1, emptyList())
            val columns = (1..minOf(n, MOST_COLUMNS)).minBy { c ->
                val rows = ceil(n / c.toFloat())
                val shape = ((w - gap * (c - 1)) / c) / ((h - gap * (rows - 1)) / rows)
                abs(ln(shape / SHAPE))
            }
            val rows = ceil(n / columns.toFloat()).toInt()
            val high = (h - gap * (rows - 1)) / rows
            val rects = ArrayList<Rect>(n)
            for (row in 0 until rows) {
                val inRow = minOf(columns, n - row * columns)
                val wide = (w - gap * (inRow - 1)) / inRow
                for (c in 0 until inRow) {
                    val left = c * (wide + gap)
                    val top = row * (high + gap)
                    rects += Rect(left, top, left + wide, top + high)
                }
            }
            return Grid(columns, rects)
        }

        /** The type a tile can carry, from how many sit on the wall and how wide they are. */
        fun size(tileWidthDp: Float, tileHeightDp: Float): TileSize = when {
            tileHeightDp >= 150f && tileWidthDp >= 240f -> TileSize.Big
            tileHeightDp >= 120f && tileWidthDp >= 170f -> TileSize.Large
            tileHeightDp >= 92f && tileWidthDp >= 130f -> TileSize.Medium
            else -> TileSize.Small
        }
    }
}
