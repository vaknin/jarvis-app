package com.kivan.jarvis.tabs

import androidx.activity.BackEventCompat
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Holding a tile: the tile is clickable, and a hold must still reach the wall's own gesture. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w412dp-h915dp-xxhdpi", application = android.app.Application::class)
class WallTouchTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private val deck = FakeDeck(Fake.deck(9))

    private fun wall() {
        rule.mainClock.autoAdvance = false
        // The wall itself: the app opens on Needs you, the wall stays behind it.
        rule.setContent { TabsTheme(dark = true) { Stage(deck, Opening(list = false)) } }
        rule.mainClock.advanceTimeBy(1200)
    }

    private fun tile(n: Int) = rule.onNodeWithContentDescription("Tab $n,", substring = true)

    private fun inATab() = rule.onAllNodesWithContentDescription("Back to the wall").fetchSemanticsNodes().isNotEmpty()

    private fun center(n: Int) = tile(n).fetchSemanticsNode().boundsInRoot.center

    // On the root, whose place never changes: a lifted tile moves under the finger.
    private fun hold(n: Int) {
        val at = center(n)
        rule.onRoot().performTouchInput { down(at) }
        rule.mainClock.advanceTimeBy(800)
    }

    private fun lift() {
        rule.onRoot().performTouchInput { up() }
        rule.mainClock.advanceTimeBy(600)
    }

    @Test
    fun aTapOpensTheTab() {
        wall()
        tile(3).performClick()
        rule.mainClock.advanceTimeBy(1000)
        assertEquals(true, inATab())
    }

    @Test
    fun aHoldOffersCloseAndDoesNotOpen() {
        wall()
        hold(3)
        lift()
        rule.onNodeWithText("Close tab").assertExists()
        assertEquals(false, inATab())
    }

    @Test
    fun aDragMovesTheTileAndItKeepsItsNumber() {
        wall()
        val from = center(3)
        val to = center(7)
        hold(3)
        for (step in 1..8) {
            rule.onRoot().performTouchInput { moveTo(from + (to - from) * (step / 8f)) }
            rule.mainClock.advanceTimeBy(100)
        }
        lift()
        assertEquals(false, inATab())
        val tiles = deck.state.value.tiles
        assertEquals(6, tiles.indexOfFirst { it.n == 3 })
        assertEquals((1..9).toSet(), tiles.map { it.n }.toSet())
    }

    @Test
    fun backFromATabReturnsToTheWall() {
        wall()
        tile(3).performClick()
        rule.mainClock.advanceTimeBy(1000)
        rule.activity.onBackPressedDispatcher.onBackPressed()
        rule.mainClock.advanceTimeBy(1000)
        assertEquals(false, inATab())
        tile(3).assertExists()
    }

    @Test
    fun aBackGestureLetGoStaysInTheTab() {
        wall()
        tile(3).performClick()
        rule.mainClock.advanceTimeBy(1000)
        val back = rule.activity.onBackPressedDispatcher
        fun edge(progress: Float) = BackEventCompat(0f, 900f, progress, BackEventCompat.EDGE_LEFT)
        rule.runOnUiThread {
            back.dispatchOnBackStarted(edge(0f))
            back.dispatchOnBackProgressed(edge(0.6f))
        }
        rule.mainClock.advanceTimeBy(200)
        rule.runOnUiThread { back.dispatchOnBackCancelled() }
        rule.mainClock.advanceTimeBy(1000)
        assertEquals(true, inATab())
    }

    @Test
    fun aTileShowsItsHeaviestStateInColour() {
        lateinit var l: Look
        rule.setContent { TabsTheme(dark = true) { l = look } }
        rule.waitForIdle()
        val tile = TileView("t", 1, "Tab")
        assertEquals(l.surface, l.fill(tile))
        assertEquals(l.newFill, l.fill(tile.copy(unread = true)))
        assertEquals(l.workingFill, l.fill(tile.copy(unread = true, working = true)))
        assertEquals(l.urgent, l.fill(tile.copy(unread = true, working = true, needs = true)))
    }
}
