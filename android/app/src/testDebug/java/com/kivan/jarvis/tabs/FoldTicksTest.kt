package com.kivan.jarvis.tabs

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Task 1266: Jarvis's ticks under his messages in the main chat, and a tap on them shows what they stand for, as the
 * laptop's Space does: a second tap hides it, a third shows it again, and the server is asked only once. Shots land in
 * build/shots.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w412dp-h915dp-xxhdpi", application = android.app.Application::class)
class FoldTicksTest {
    @get:Rule val rule = createComposeRule()

    private fun open(): FakeDeck {
        val deck = FakeDeck(Fake.foldTalk(ticked = true))
        rule.mainClock.autoAdvance = false
        rule.setContent {
            TabsTheme(dark = true) {
                Box(Modifier.fillMaxSize().background(look.canvas).padding(top = 40.dp, bottom = 12.dp)) { Stage(deck, Opening(fold = true)) }
            }
        }
        rule.mainClock.advanceTimeBy(1200)
        return deck
    }

    /** The ticks of his [i]th ticked message from the top: the list lays out from the bottom, so its order isn't the screen's. */
    private fun tap(i: Int) {
        val nodes = rule.onAllNodesWithTag("ticks").fetchSemanticsNodes()
        val byTop = nodes.indices.sortedBy { nodes[it].boundsInRoot.top }
        rule.onAllNodesWithTag("ticks")[byTop[i]].performClick()
        rule.mainClock.advanceTimeBy(300)
    }

    private fun top(i: Int): Float = rule.onAllNodesWithTag("ticks").fetchSemanticsNodes().map { it.boundsInRoot.top }.sorted()[i]

    private fun words() = rule.onAllNodesWithTag("tick-words").fetchSemanticsNodes().size

    @Test
    fun aTapShowsTheWordsAndTheServerIsAskedOnce() {
        val deck = open()
        // Two of his messages carry ticks; the one between them doesn't.
        assertEquals(2, rule.onAllNodesWithTag("ticks").fetchSemanticsNodes().size)
        assertEquals(0, words())
        val before = top(0)
        tap(0)
        assertEquals("the ticks stay under his finger as the words open", before, top(0), 1f)
        rule.onNodeWithTag("tick-words").assertTextEquals(Fake.tickWords.getValue(tickKey("t0", 30)))
        rule.onRoot().captureRoboImage("build/shots/fold-tick-open.png")
        tap(0)
        assertEquals("a second tap hides them", 0, words())
        tap(0)
        rule.onNodeWithTag("tick-words").assertTextEquals(Fake.tickWords.getValue(tickKey("t0", 30)))
        assertEquals("asked once", listOf("t0" to 30L), deck.tickAsks)
    }

    @Test
    fun aTickWithNoWordsSaysSo() {
        val deck = open()
        tap(1)
        rule.onNodeWithTag("tick-words").assertTextEquals(NO_TICK_WORDS)
        tap(1)
        tap(1)
        assertEquals(listOf("t0" to 50L), deck.tickAsks)
    }
}
