package com.kivan.jarvis.tabs

import android.app.Application
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasSetTextAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Holding paragraphs across answers and sending them, as on the laptop. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w412dp-h915dp-xxhdpi", application = Application::class)
class MarkQuoteTest {
    @get:Rule val rule = createComposeRule()

    private val first = "First paragraph with **bold** and a [link](https://example.com/a)."
    private val firstQuote = "First paragraph with bold and a link (https://example.com/a)."
    private val second = "Second answer, its only paragraph."

    private fun deck(): FakeDeck {
        val base = Fake.deck(9)
        val t1 = base.tabs.getValue("t1").copy(
            entries = listOf(Entry.Answer("a1", 41, "$first\n\nThe rest of it."), Entry.Answer("a2", 57, second)),
            work = null,
        )
        val deck = FakeDeck(base.copy(tabs = base.tabs + ("t1" to t1)))
        rule.mainClock.autoAdvance = false
        rule.setContent { TabsTheme(dark = true) { Stage(deck, Opening(tab = "t1")) } }
        rule.mainClock.advanceTimeBy(600)
        return deck
    }

    // In the order they read; the record is built newest first.
    private fun paragraph(i: Int): SemanticsNodeInteraction = rule.onAllNodesWithTag("paragraph")[listOf(1, 2, 0)[i]]

    private fun hold(i: Int) {
        paragraph(i).performTouchInput { longClick(durationMillis = 800) }
        rule.mainClock.advanceTimeBy(300)
    }

    // The next tab is composed beside this one, with its own box.
    private fun box() = rule.onAllNodes(hasSetTextAction())[0]

    private fun FakeDeck.marks() = state.value.marks["t1"].orEmpty().map { it.quote }

    private fun FakeDeck.sent() = state.value.tabs.getValue("t1").entries.last { it is Entry.Yours } as Entry.Yours

    @Test
    fun holdMarksAParagraphInTheLaptopsWords() {
        val deck = deck()
        hold(0)
        assertEquals(listOf(firstQuote), deck.marks())
        rule.onNodeWithTag("marked").assertIsDisplayed()
    }

    @Test
    fun marksGoAcrossAnswersInTheOrderTheyWereSaid() {
        val deck = deck()
        hold(2)
        hold(0)
        assertEquals(listOf(firstQuote, second), deck.marks())
        rule.onNodeWithText("● 2 marked").assertIsDisplayed()
    }

    @Test
    fun sendCarriesBothQuotesThenHisWordsAndClearsTheMarks() {
        val deck = deck()
        hold(0)
        hold(2)
        box().performTextInput("both of these")
        rule.mainClock.advanceTimeBy(300)
        rule.onNodeWithContentDescription("Send").performClick()
        rule.mainClock.advanceTimeBy(300)
        val sent = deck.sent()
        assertEquals(listOf(Quoted(41, firstQuote, ""), Quoted(57, second, "")), sent.quotes)
        assertEquals("both of these", sent.text)
        assertEquals(emptyList<String>(), deck.marks())
    }

    @Test
    fun marksAloneSendAsOnTheLaptop() {
        val deck = deck()
        hold(2)
        rule.onNodeWithContentDescription("Send").performClick()
        rule.mainClock.advanceTimeBy(300)
        assertEquals(listOf(Quoted(57, second, "")), deck.sent().quotes)
        assertEquals("", deck.sent().text)
    }

    @Test
    fun holdAgainTapOrTheChipUnmarks() {
        val deck = deck()
        hold(0)
        hold(0)
        assertEquals(emptyList<String>(), deck.marks())
        hold(1)
        paragraph(1).performTouchInput { click() }
        rule.mainClock.advanceTimeBy(300)
        assertEquals(emptyList<String>(), deck.marks())
        hold(2)
        rule.onNodeWithContentDescription("Remove the quote: ${second.take(40)}").performClick()
        rule.mainClock.advanceTimeBy(300)
        assertEquals(emptyList<String>(), deck.marks())
    }

    @Test
    fun aTapOnAnUnmarkedParagraphMarksNothing() {
        val deck = deck()
        paragraph(0).performTouchInput { click() }
        rule.mainClock.advanceTimeBy(300)
        assertEquals(emptyList<String>(), deck.marks())
    }

    @Test
    fun holdThenDragIsASelectionNotAMark() {
        val deck = deck()
        paragraph(0).performTouchInput {
            down(center)
            advanceEventTime(700)
            moveBy(Offset(200f, 0f))
            advanceEventTime(100)
            up()
        }
        rule.mainClock.advanceTimeBy(300)
        assertEquals(emptyList<String>(), deck.marks())
    }

    private fun swipe(i: Int, by: Float) {
        paragraph(i).performTouchInput {
            down(center)
            repeat(6) {
                advanceEventTime(16)
                moveBy(Offset(by / 6, 0f))
            }
            up()
        }
        rule.mainClock.advanceTimeBy(400)
    }

    private fun type(words: String) {
        box().performTextInput(words)
        rule.mainClock.advanceTimeBy(300)
    }

    private fun send() {
        rule.onNodeWithContentDescription("Send").performClick()
        rule.mainClock.advanceTimeBy(300)
    }

    @Test
    fun aSwipeRepliesToThatParagraphAndSendGoesAtOnce() {
        val deck = deck()
        swipe(2, 400f)
        rule.onNodeWithTag("replying").assertIsDisplayed()
        type("this one")
        send()
        assertEquals(listOf(Quoted(57, second, "this one")), deck.sent().quotes)
        assertEquals("", deck.sent().text)
        assertEquals(emptyList<QuoteMark>(), deck.state.value.marks["t1"].orEmpty())
        rule.onNodeWithTag("replying").assertDoesNotExist()
    }

    private fun FakeDeck.yours() = state.value.tabs.getValue("t1").entries.count { it is Entry.Yours }

    private fun holdIt() {
        rule.onNodeWithText("Hold").performClick()
        rule.mainClock.advanceTimeBy(300)
    }

    @Test
    fun aHeldReplyStaysHereQuietAndEditable() {
        val deck = deck()
        val before = deck.yours()
        swipe(0, 400f)
        type("note one")
        holdIt()
        assertEquals(listOf(QuoteMark(41, 0, firstQuote, "note one", quiet = true)), deck.state.value.marks["t1"])
        assertEquals("nothing goes yet", before, deck.yours())
        rule.onNodeWithTag("replying").assertDoesNotExist()
    }

    @Test
    fun anEditedHeldReplyReplacesItStaysEditableAndGoesOnce() {
        val deck = deck()
        val before = deck.yours()
        swipe(0, 400f)
        type("note one")
        holdIt()
        // Back to it: a tap on the held paragraph opens his words again.
        paragraph(0).performTouchInput { click() }
        rule.mainClock.advanceTimeBy(300)
        rule.onNodeWithTag("replying").assertIsDisplayed()
        box().performTextClearance()
        type("note two")
        // Half-edited it isn't quiet: it can't go by itself mid-edit.
        assertEquals(listOf(false), deck.state.value.marks["t1"]!!.map { it.quiet })
        holdIt()
        assertEquals(listOf(QuoteMark(41, 0, firstQuote, "note two", quiet = true)), deck.state.value.marks["t1"])
        assertEquals(before, deck.yours())
        // And again: still his to edit.
        paragraph(0).performTouchInput { click() }
        rule.mainClock.advanceTimeBy(300)
        rule.onNodeWithTag("replying").assertIsDisplayed()
        box().performTextClearance()
        type("note three")
        holdIt()
        send()
        assertEquals("one message", before + 1, deck.yours())
        assertEquals(listOf(Quoted(41, firstQuote, "note three")), deck.sent().quotes)
        assertEquals(emptyList<QuoteMark>(), deck.state.value.marks["t1"].orEmpty())
    }

    private fun longSend() {
        rule.onNodeWithContentDescription("Send").performTouchInput { longClick(durationMillis = 800) }
        rule.mainClock.advanceTimeBy(300)
    }

    @Test
    fun aLongPressOnSendHoldsTheOpenReply() {
        val deck = deck()
        val before = deck.yours()
        swipe(0, 400f)
        type("note one")
        longSend()
        assertEquals(listOf(QuoteMark(41, 0, firstQuote, "note one", quiet = true)), deck.state.value.marks["t1"])
        assertEquals("nothing goes yet", before, deck.yours())
        rule.onNodeWithTag("replying").assertDoesNotExist()
    }

    @Test
    fun aLongPressOnSendHoldsPlainWordsQuiet() {
        val deck = deck()
        type("for later")
        longSend()
        assertEquals("for later", deck.sent().text)
        assertTrue("they wait for send-all", deck.state.value.tabs.getValue("t1").waiting)
        assertEquals("", deck.state.value.typed["t1"].orEmpty())
    }

    @Test
    fun sendWithNothingTypedTakesWhatsHeld() {
        val deck = deck()
        swipe(2, 400f)
        type("mine")
        holdIt()
        send()
        assertEquals(listOf(Quoted(57, second, "mine")), deck.sent().quotes)
        assertEquals(emptyList<QuoteMark>(), deck.state.value.marks["t1"].orEmpty())
    }

    @Test
    fun aTapOnWhatHeSaidPutsItInTheBoxNotSent() {
        val deck = deck()
        val before = deck.yours()
        deck.dictate()
        deck.heard("said aloud")
        rule.mainClock.advanceTimeBy(300)
        rule.onAllNodesWithText("said aloud")[0].performClick()
        rule.mainClock.advanceTimeBy(300)
        assertEquals("said aloud", deck.state.value.typed["t1"])
        assertEquals("", deck.state.value.heard)
        assertFalse(deck.state.value.dictating)
        assertEquals("nothing sent", before, deck.yours())
    }

    @Test
    fun cancelDropsANewReply() {
        val deck = deck()
        swipe(2, 400f)
        type("gone")
        rule.onNodeWithContentDescription("Cancel this reply").performClick()
        rule.mainClock.advanceTimeBy(300)
        assertEquals(emptyList<String>(), deck.marks())
    }

    @Test
    fun aShortSwipeOrAScrollRepliesToNothing() {
        deck()
        swipe(2, 60f)
        rule.onNodeWithTag("replying").assertDoesNotExist()
        paragraph(2).performTouchInput {
            down(center)
            repeat(6) {
                advanceEventTime(16)
                moveBy(Offset(30f, -60f))
            }
            up()
        }
        rule.mainClock.advanceTimeBy(400)
        rule.onNodeWithTag("replying").assertDoesNotExist()
    }

    @Test
    fun aSwipeLeftStillGoesToTheNextTab() {
        deck()
        swipe(2, -700f)
        rule.mainClock.advanceTimeBy(1000)
        rule.onNodeWithTag("replying").assertDoesNotExist()
        rule.onNodeWithText(second).assertIsNotDisplayed()
    }

    @Test
    fun aDraftFromTheOldReplyPageBecomesHeldReplies() {
        val base = Fake.deck(9)
        val t1 = base.tabs.getValue("t1").copy(entries = listOf(Entry.Answer("a1", 41, "$first\n\nThe rest of it.")))
        val draft = ReplyDraft(41, mapOf(1 to Note("", "under it")), "at the end")
        val state = base.copy(tabs = base.tabs + ("t1" to t1), typed = mapOf("t1" to "typed"), drafts = mapOf(draftKey("t1", 41) to draft))
        val folded = state.foldDrafts("t1")
        assertEquals(listOf(QuoteMark(41, 1, "The rest of it.", "under it")), folded.marks["t1"])
        assertEquals("typed\n\nat the end", folded.typed["t1"])
        assertEquals(emptyMap<String, ReplyDraft>(), folded.drafts)
    }

    @Test
    fun aDraftNeverWipesARepliesWords() {
        val base = Fake.deck(9)
        val t1 = base.tabs.getValue("t1").copy(entries = listOf(Entry.Answer("a1", 41, "One.\n\nTwo.")))
        val state = base.copy(
            tabs = base.tabs + ("t1" to t1),
            marks = mapOf("t1" to listOf(QuoteMark(41, 1, "Two.", "held"))),
            drafts = mapOf(draftKey("t1", 41) to ReplyDraft(41, mapOf(1 to Note("Two.", "")))),
        )
        assertEquals(listOf("held"), state.foldDrafts("t1").marks.getValue("t1").map { it.text })
    }

    @Test
    fun twoParagraphsThatSayTheSameAreTwoMarks() {
        val a = QuoteMark(41, 0, "Done.")
        val b = QuoteMark(41, 3, "Done.")
        assertEquals(listOf(a, b), listOf(a).toggled(b))
    }

    @Test
    fun aSwipeDraggedBackRepliesToNothing() {
        deck()
        paragraph(2).performTouchInput {
            down(center)
            repeat(6) {
                advanceEventTime(16)
                moveBy(Offset(70f, 0f))
            }
            repeat(6) {
                advanceEventTime(16)
                moveBy(Offset(-70f, 0f))
            }
            up()
        }
        rule.mainClock.advanceTimeBy(400)
        rule.onNodeWithTag("replying").assertDoesNotExist()
    }

    @Test
    fun aDraftWaitsForItsAnswer() {
        val state = DeckState(drafts = mapOf(draftKey("t1", 41) to ReplyDraft(41, end = "mine")))
        assertEquals(state.drafts, state.foldDrafts("t1").drafts)
    }

    @Test
    fun marksOutliveTheProcess() {
        val state = DeckState(marks = mapOf("t1" to listOf(QuoteMark(41, 2, "words"), QuoteMark(57, 0, "more", "my reply"))))
        assertEquals(state.marks, Saved.parse(Saved.of(state).json())?.marks)
    }
}
