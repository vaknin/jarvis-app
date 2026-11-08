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

    private fun open(): FakeDeck {
        val deck = FakeDeck(Fake.foldTalk())
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

    private fun said(words: String): SemanticsNodeInteraction = rule.onAllNodesWithText(words)[0]

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
}
