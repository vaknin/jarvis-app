package com.kivan.jarvis.tabs

import androidx.compose.ui.test.junit4.createComposeRule
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
    @get:Rule val rule = createComposeRule()

    private val deck = FakeDeck(Fake.deck(9))

    private fun wall() {
        rule.mainClock.autoAdvance = false
        rule.setContent { TabsTheme(dark = true) { Stage(deck) } }
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
}
