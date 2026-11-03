package com.kivan.jarvis.tabs

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** t116: a card of several questions sends only what he tapped; t67: Something else waits for his words. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w412dp-h915dp-xxhdpi", application = android.app.Application::class)
class ManyQuestionsTest {
    @get:Rule val rule = createComposeRule()

    private val open = UInt.MAX_VALUE

    @Test fun onlyWhatHeTappedGoesAndASkippedOneStaysOpen() {
        val c = Fake.intake
        val words = List(5) { "" }
        assertNull("nothing tapped, nothing to send", c.reply(List(5) { null }, words))
        assertEquals(listOf(open, 1u) to "", c.reply(listOf(null, 1, null, null, null), words))
        // Something else counts only with his words, which go under its question.
        val picks = listOf(null, null, 2, null, null)
        assertNull(c.reply(picks, words))
        assertEquals(listOf(open, open, 2u) to "Keys in dark mode?: glow on press", c.reply(picks, listOf("", "", " glow on press ", "", "")))
    }

    private fun stage(deck: FakeDeck) {
        rule.mainClock.autoAdvance = false
        rule.setContent { TabsTheme { Stage(deck, Opening()) } }
        rule.mainClock.advanceTimeBy(1200)
    }

    private fun tap(text: String) {
        rule.onAllNodesWithText(text)[0].performClick()
        rule.mainClock.advanceTimeBy(300)
    }

    @Test fun theCardSendsHisTapsAlone() {
        val deck = FakeDeck(Fake.deck(3).copy(cards = listOf(Fake.intake)))
        stage(deck)
        tap("Top bar")
        tap("Send 1 · the rest stay open")
        assertEquals(listOf(Triple("t6/q2", listOf(open, 1u), "")), deck.each)
    }

    @Test fun somethingElseWithNothingWrittenOpensTheNote() {
        val c = CardView(listOf(CardTab("t9", "Tab", "q1")), "Which?", listOf("A", ELSE))
        val deck = FakeDeck(Fake.deck(3).copy(cards = listOf(c)))
        stage(deck)
        tap(ELSE)
        assertTrue("nothing sent", deck.answers.isEmpty())
        rule.onAllNodesWithTag("note")[0].performTextInput("my way")
        rule.mainClock.advanceTimeBy(300)
        tap(ELSE)
        assertEquals(listOf(Triple("t9/q1", 1 as Int?, "my way")), deck.answers)
    }
}
