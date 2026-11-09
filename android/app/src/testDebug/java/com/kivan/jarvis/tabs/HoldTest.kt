package com.kivan.jarvis.tabs

import android.app.Application
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Hold is there for any message he writes, quoted or not, in a thread and in the main chat: one pill beside Send
 * (Aviv: "I don't have a hold button if I'm not replying to some message").
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w412dp-h915dp-xxhdpi", application = Application::class)
class HoldTest {
    @get:Rule val rule = createComposeRule()

    private fun thread(): FakeDeck {
        val base = Fake.deck(9)
        val t1 = base.tabs.getValue("t1").copy(entries = listOf(Entry.Answer("a1", 41, "One answer, its only paragraph.")), work = null)
        val deck = FakeDeck(base.copy(tabs = base.tabs + ("t1" to t1)))
        rule.mainClock.autoAdvance = false
        rule.setContent { TabsTheme(dark = true) { Stage(deck, Opening(tab = "t1")) } }
        rule.mainClock.advanceTimeBy(600)
        return deck
    }

    private fun fold(): FakeDeck {
        val deck = FakeDeck(Fake.foldTalk(bullets = true))
        rule.mainClock.autoAdvance = false
        rule.setContent {
            TabsTheme(dark = true) {
                Box(Modifier.fillMaxSize().background(look.canvas).padding(top = 40.dp, bottom = 12.dp)) { Stage(deck, Opening(fold = true)) }
            }
        }
        rule.mainClock.advanceTimeBy(1200)
        return deck
    }

    // The next tab is composed beside this one, with its own box: the first is the one on screen.
    private fun box() = rule.onAllNodes(hasSetTextAction() and !hasTestTag("quote-part"))[0]

    private fun type(words: String) {
        box().performTextInput(words)
        rule.mainClock.advanceTimeBy(300)
    }

    private fun holds() = rule.onAllNodesWithText("Hold").fetchSemanticsNodes().size

    private fun hold() {
        rule.onNodeWithText("Hold").assertIsDisplayed().performClick()
        rule.mainClock.advanceTimeBy(600)
    }

    private fun send() {
        rule.onNodeWithContentDescription("Send").performClick()
        rule.mainClock.advanceTimeBy(300)
    }

    private fun FakeDeck.tab(id: String) = state.value.tabs.getValue(id)

    private fun FakeDeck.yours(id: String) = tab(id).entries.filterIsInstance<Entry.Yours>()

    @Test
    fun holdPillOnPlainThreadMessage() {
        val deck = thread()
        val before = deck.yours("t1").size
        assertEquals("no Hold before there are words", 0, holds())
        type("later please")
        assertEquals(0, rule.onAllNodesWithTag("replying").fetchSemanticsNodes().size)
        hold()
        val mine = deck.yours("t1")
        assertEquals("one held message", before + 1, mine.size)
        assertEquals("later please", mine.last().text)
        assertEquals(emptyList<Quoted>(), mine.last().quotes)
        assertTrue("it waits for send-all", deck.tab("t1").waiting)
        assertNull("nothing sent: no turn started", deck.tab("t1").work)
        assertEquals("", deck.state.value.typed["t1"].orEmpty())
        assertEquals("the Hold goes with the words", 0, holds())
    }

    @Test
    fun noHoldPillOnEmptyBox() {
        thread()
        assertEquals(0, holds())
        type("x")
        assertEquals(1, holds())
        box().performTextClearance()
        rule.mainClock.advanceTimeBy(300)
        assertEquals("cleared, no Hold", 0, holds())
    }

    @Test
    fun holdPillIsTheOnlyHoldOnAReply() {
        val deck = thread()
        rule.onAllNodesWithTag("paragraph")[0].performTouchInput {
            down(center)
            repeat(6) {
                advanceEventTime(16)
                moveBy(Offset(70f, 0f))
            }
            up()
        }
        rule.mainClock.advanceTimeBy(400)
        rule.onAllNodesWithTag("replying")[0].assertIsDisplayed()
        type("note")
        assertEquals("one Hold on screen", 1, holds())
        hold()
        assertEquals(listOf(QuoteMark(41, 0, "One answer, its only paragraph.", "note", quiet = true)), deck.state.value.marks["t1"])
    }

    // The main chat: the same pill, through the same box as a thread.

    private fun swipe(words: String) {
        rule.onAllNodesWithText(words, useUnmergedTree = true)[0].performTouchInput {
            down(center)
            repeat(6) {
                advanceEventTime(16)
                moveBy(Offset(70f, 0f))
            }
            up()
        }
        rule.mainClock.advanceTimeBy(400)
    }

    @Test
    fun mainChatHoldsPlainWords() {
        val deck = fold()
        val before = deck.yours("t0").size
        assertEquals(0, holds())
        type("hold this one")
        assertEquals(0, rule.onAllNodesWithTag("replying").fetchSemanticsNodes().size)
        hold()
        val mine = deck.yours("t0")
        assertEquals(before + 1, mine.size)
        assertEquals("hold this one", mine.last().text)
        assertTrue(deck.tab("t0").waiting)
        assertEquals("", deck.state.value.typed[WALL].orEmpty())
    }

    @Test
    fun mainChatHoldsQuoteReply() {
        val deck = fold()
        val before = deck.yours("t0").size
        swipe("lower, two steps down")
        type("this one")
        assertEquals(1, holds())
        hold()
        assertEquals(listOf(QuoteMark(80, 2, "lower, two steps down", "this one", quiet = true)), deck.state.value.marks.getValue("t0"))
        assertEquals("nothing goes yet", before, deck.yours("t0").size)
        send()
        assertEquals(listOf(Quoted(80, "lower, two steps down", "this one")), deck.yours("t0").last().quotes)
    }

    @Test
    fun mainChatEmptySendReleases() {
        val deck = fold()
        type("later please")
        hold()
        assertTrue(deck.tab("t0").waiting)
        val before = deck.yours("t0").size
        send()
        assertFalse("what he held goes in", deck.tab("t0").waiting)
        assertEquals("nothing new sent", before, deck.yours("t0").size)
    }
}
