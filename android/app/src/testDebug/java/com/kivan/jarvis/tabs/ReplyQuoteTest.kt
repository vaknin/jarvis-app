package com.kivan.jarvis.tabs

import android.app.Application
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The reply page, typed on and sent for real: the bug was a bare "same" with no quote. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w412dp-h915dp-xxhdpi", application = Application::class)
class ReplyQuoteTest {
    @get:Rule val rule = createComposeRule()

    @Test
    fun wordsInAnythingElseGoWithTheQuoteAndShowAboveThem() {
        val deck = FakeDeck(Fake.deck(9))
        // The tab's work line never stops moving, so the clock goes by hand.
        rule.mainClock.autoAdvance = false
        rule.setContent { TabsTheme(dark = true) { Stage(deck, Opening(tab = "t3", reply = "a1")) } }
        rule.mainClock.advanceTimeBy(600)
        rule.onNodeWithTag("reply").performScrollToNode(hasContentDescription("Anything else"))
        rule.onNode(hasContentDescription("Anything else")).performTextInput("same")
        rule.mainClock.advanceTimeBy(600)
        rule.onNodeWithText("Send 1 answer").performClick()
        rule.mainClock.advanceTimeBy(600)
        val sent = deck.state.value.tabs.getValue("t3").entries.last { it is Entry.Yours } as Entry.Yours
        assertEquals("", sent.text)
        assertEquals("same", sent.quotes.single().text)
        assertTrue(sent.quotes.single().quote.isNotEmpty())
        // Once in the answer, once above his words.
        rule.onAllNodesWithText(sent.quotes.single().quote).assertCountEquals(2)
        rule.onNodeWithText("same").assertIsDisplayed()
    }

    @Test
    fun aDraftOnOneAnswerSurvivesAReplyToAnother() {
        val deck = FakeDeck(Fake.deck(9))
        deck.draft("t1", 41, ReplyDraft(41, end = "first"))
        deck.draft("t1", 57, ReplyDraft(57, end = "second"))
        assertEquals("first", deck.state.value.drafts[draftKey("t1", 41)]?.end)
        assertEquals("second", deck.state.value.drafts[draftKey("t1", 57)]?.end)
    }
}
