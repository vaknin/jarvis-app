package com.kivan.jarvis.tabs

import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextInputSelection
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Quoting in the phone's main chat, as in a thread: a swipe right on his message or Jarvis's quotes it above the box;
 * a hold offers Quote part, Copy and Open thread. Pictures land in build/shots beside ScreensTest's.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w412dp-h915dp-xxhdpi", application = android.app.Application::class)
class FoldQuoteTest {
    @get:Rule val rule = createComposeRule()

    private val his = "and the voice should sound calmer"
    private val jarvis = "A calmer voice, slower and lower. Trying two."

    private fun open(bullets: Boolean = false): FakeDeck {
        val deck = FakeDeck(Fake.foldTalk(bullets = bullets))
        rule.mainClock.autoAdvance = false
        rule.setContent {
            TabsTheme(dark = true) {
                // Robolectric has no system bars: leave the room the phone's would take, as ScreensTest does.
                Box(Modifier.fillMaxSize().background(look.canvas).padding(top = 40.dp, bottom = 12.dp)) { Stage(deck, Opening(fold = true)) }
            }
        }
        rule.mainClock.advanceTimeBy(1200)
        return deck
    }

    // Unmerged: the hold menu's press merges a whole message into one node, and a swipe goes on one part of it.
    private fun said(words: String): SemanticsNodeInteraction = rule.onAllNodesWithText(words, useUnmergedTree = true)[0]

    private fun swipe(words: String) {
        said(words).performTouchInput {
            down(center)
            repeat(6) {
                advanceEventTime(16)
                moveBy(Offset(70f, 0f))
            }
            up()
        }
        rule.mainClock.advanceTimeBy(400)
    }

    private fun hold(words: String) {
        said(words).performTouchInput { longClick() }
        rule.mainClock.advanceTimeBy(600)
    }

    private fun strip(): String = rule.onNodeWithTag("replying").fetchSemanticsNode().config[SemanticsProperties.Text].joinToString("") { it.text }

    private fun box() = rule.onAllNodes(hasSetTextAction() and !hasTestTag("quote-part"))[0]

    private fun type(words: String) {
        box().performTextInput(words)
        rule.mainClock.advanceTimeBy(300)
    }

    private fun send() {
        rule.onNodeWithContentDescription("Send").performClick()
        rule.mainClock.advanceTimeBy(300)
    }

    private fun FakeDeck.sent() = state.value.tabs.getValue("t0").entries.last { it is Entry.Yours } as Entry.Yours

    private fun shot(name: String) {
        rule.mainClock.advanceTimeBy(600)
        rule.onRoot().captureRoboImage("build/shots/$name.png")
    }

    @Test
    fun swipeQuotesHisAndJarvisMessages() {
        val deck = open()
        // His own message: quoted above the box, his reply goes with it.
        swipe(his)
        assertEquals(his, strip())
        type("yes, both of them")
        send()
        assertEquals(listOf(Quoted(50, his, "yes, both of them")), deck.sent().quotes)
        assertEquals(0, rule.onAllNodesWithTag("replying").fetchSemanticsNodes().size)
        // Jarvis's reply: cancel clears it, and swiping again quotes it.
        swipe(jarvis)
        assertEquals(jarvis, strip())
        rule.onNodeWithContentDescription("Cancel this reply").performClick()
        rule.mainClock.advanceTimeBy(300)
        assertEquals(0, rule.onAllNodesWithTag("replying").fetchSemanticsNodes().size)
        swipe(jarvis)
        assertEquals(jarvis, strip())
        type("try the lower one first")
        shot("fold-quote-swipe-reply")
        send()
        assertEquals(listOf(Quoted(60, jarvis, "try the lower one first")), deck.sent().quotes)
        assertEquals("", deck.sent().text)
    }

    @Test
    fun holdMenuQuotePartCopyOpenThread() {
        val deck = open()
        // The menu is a popup Robolectric doesn't draw: its three items are held by their words.
        hold(jarvis)
        listOf("Quote part", "Copy", "Open thread").forEach { rule.onAllNodesWithText(it)[0].assertExists() }

        // Quote part: the message turns selectable in place, and Quote this quotes only the words picked.
        rule.onAllNodesWithText("Quote part")[0].performClick()
        rule.mainClock.advanceTimeBy(300)
        rule.onAllNodesWithText("Select the words to quote")[0].assertExists()
        val part = "slower and lower"
        val at = jarvis.indexOf(part)
        rule.onNodeWithTag("quote-part").performTextInputSelection(TextRange(at, at + part.length))
        shot("fold-quote-part-picking")
        rule.onAllNodesWithText("Quote this")[0].performClick()
        rule.mainClock.advanceTimeBy(300)
        assertEquals(part, strip())
        assertEquals(0, rule.onAllNodesWithTag("quote-part").fetchSemanticsNodes().size)
        type("this one")
        shot("fold-quote-part")
        send()
        assertEquals(listOf(Quoted(60, part, "this one")), deck.sent().quotes)

        // Copy: the whole message on the clipboard, and a one-line notice.
        hold(jarvis)
        rule.onAllNodesWithText("Copy")[0].performClick()
        rule.mainClock.advanceTimeBy(300)
        val clip = ApplicationProvider.getApplicationContext<Context>().getSystemService(ClipboardManager::class.java).primaryClip
        assertEquals(jarvis, clip?.getItemAt(0)?.text.toString())
        rule.onAllNodesWithText("Copied")[0].assertExists()

        // Open thread: the move-subject flow, its bar asking before anything moves.
        hold(his)
        rule.onAllNodesWithText("Open thread")[0].performClick()
        rule.mainClock.advanceTimeBy(600)
        rule.onNodeWithTag("move-bar").assertExists()
    }

    @Test
    fun swipeQuotesOneBullet() {
        val deck = open(bullets = true)
        // Jarvis's lead paragraph and three bullets: the second bullet alone is quoted, its place with it.
        swipe("lower, two steps down")
        assertEquals("lower, two steps down", strip())
        assertEquals(listOf(QuoteMark(80, 2, "lower, two steps down")), deck.state.value.marks.getValue("t0"))
        type("this one")
        shot("fold-quote-swipe-bullet")
        send()
        assertEquals(listOf(Quoted(80, "lower, two steps down", "this one")), deck.sent().quotes)
    }

    @Test
    fun swipeQuotesOneLineOfHis() {
        val deck = open(bullets = true)
        swipe("second line")
        assertEquals("second line", strip())
        assertEquals(1, deck.state.value.marks.getValue("t0").single().block)
        type("yes")
        send()
        assertEquals(listOf(Quoted(70, "second line", "yes")), deck.sent().quotes)
    }

    @Test
    fun hisLinesDrawRaw() {
        open(bullets = true)
        // As he typed them: the markup kept, each line its own, nothing joined.
        listOf("- **first** one", "second line", "third").forEach { assertEquals(it, 1, rule.onAllNodesWithText(it, useUnmergedTree = true).fetchSemanticsNodes().size) }
        listOf("first one", "- **first** one\nsecond line\n\nthird", "- **first** one second line").forEach {
            assertEquals(it, 0, rule.onAllNodesWithText(it, useUnmergedTree = true).fetchSemanticsNodes().size)
        }
    }

    // The main chat runs on the thread's reply box (Aviv: "when I open the Jarvis thread it does work as expected").

    private fun FakeDeck.yours() = state.value.tabs.getValue("t0").entries.count { it is Entry.Yours }

    private fun FakeDeck.waiting() = state.value.tabs.getValue("t0").waiting

    private fun holdPill() {
        rule.onNodeWithText("Hold").performClick()
        rule.mainClock.advanceTimeBy(300)
    }

    private fun longSend() {
        rule.onNodeWithContentDescription("Send").performTouchInput { longClick(durationMillis = 800) }
        rule.mainClock.advanceTimeBy(300)
    }

    @Test
    fun mainChatHoldsReply() {
        val deck = open(bullets = true)
        val before = deck.yours()
        swipe("lower, two steps down")
        type("this one")
        holdPill()
        assertEquals(listOf(QuoteMark(80, 2, "lower, two steps down", "this one", quiet = true)), deck.state.value.marks.getValue("t0"))
        assertEquals("nothing goes yet", before, deck.yours())
        assertEquals(0, rule.onAllNodesWithTag("replying").fetchSemanticsNodes().size)
        rule.onNodeWithTag("marked").assertExists()
    }

    @Test
    fun mainChatHoldsPlainWords() {
        val deck = open()
        type("later please")
        longSend()
        assertEquals("later please", deck.sent().text)
        assertTrue("they wait for send-all", deck.waiting())
        assertEquals("", deck.state.value.typed[WALL].orEmpty())
    }

    @Test
    fun mainChatEmptySendReleases() {
        val deck = open()
        type("later please")
        longSend()
        assertTrue(deck.waiting())
        val before = deck.yours()
        send()
        assertFalse("what he held goes in", deck.waiting())
        assertEquals("nothing new sent", before, deck.yours())
    }

    @Test
    fun mainChatSwipeKeepsOtherMarks() {
        val deck = open(bullets = true)
        swipe("lower, two steps down")
        type("this one")
        holdPill()
        swipe("slower, at nine tenths")
        assertEquals("slower, at nine tenths", strip())
        assertEquals(
            listOf(QuoteMark(80, 1, "slower, at nine tenths"), QuoteMark(80, 2, "lower, two steps down", "this one", quiet = true)),
            deck.state.value.marks.getValue("t0"),
        )
        type("and this")
        send()
        assertEquals(
            listOf(Quoted(80, "slower, at nine tenths", "and this"), Quoted(80, "lower, two steps down", "this one")),
            deck.sent().quotes,
        )
        assertEquals(emptyList<QuoteMark>(), deck.state.value.marks["t0"].orEmpty())
    }
}
