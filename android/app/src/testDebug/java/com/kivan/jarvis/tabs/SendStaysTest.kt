package com.kivan.jarvis.tabs

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.dp
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
 * Send keeps the view where he is reading, in a thread and in the main chat, as on the laptop: scrolled up, the
 * message he was on stays put; at the newest already, his new message comes into view. Pictures in build/shots.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w412dp-h915dp-xxhdpi", application = android.app.Application::class)
class SendStaysTest {
    @get:Rule val rule = createComposeRule()

    private val sent = "one more thing about the chords"

    /** A record long enough to scroll several screens: his lines and Jarvis's answers, each with a number to find it by. */
    private fun long(start: Long): List<Entry> = (1..30).map { i ->
        val at = start + i * 60_000L
        if (i % 2 == 1) Entry.Yours("f$i", "Line $i from him, asking about the next part", offset = i * 10L, at = at)
        else Entry.Answer("f$i", i * 10L, "Line $i from Jarvis, a short answer that wraps onto a second line on the phone.", at = at)
    }

    private fun thread(): FakeDeck {
        val base = Fake.deck(3)
        val t1 = base.tabs.getValue("t1")
        return show(base.copy(tabs = base.tabs + ("t1" to t1.copy(entries = long(0), work = null))), Opening(tab = "t1"), "record")
    }

    private fun main(): FakeDeck {
        val base = Fake.foldTalk()
        val t0 = base.tabs.getValue("t0")
        val start = (Fake.talk.last() as Entry.Answer).at
        return show(base.copy(tabs = base.tabs + ("t0" to t0.copy(entries = Fake.talk + long(start)))), Opening(fold = true), "fold-home")
    }

    private var listTag = ""

    private fun show(state: DeckState, opening: Opening, tag: String): FakeDeck {
        val deck = FakeDeck(state)
        listTag = tag
        rule.mainClock.autoAdvance = false
        rule.setContent {
            TabsTheme(dark = true) {
                // Robolectric has no system bars: leave the room the phone's would take, as ScreensTest does.
                Box(Modifier.fillMaxSize().background(look.canvas).padding(top = 40.dp, bottom = 12.dp)) { Stage(deck, opening) }
            }
        }
        rule.mainClock.advanceTimeBy(1200)
        return deck
    }

    // The next tab's page sits beside this one with its own record: the first is the one on screen.
    private fun list() = rule.onAllNodesWithTag(listTag)[0]

    private fun bounds(words: String): Rect = rule.onAllNodesWithText(words, substring = true, useUnmergedTree = true)[0].fetchSemanticsNode().boundsInRoot

    private fun shown(words: String): Boolean {
        val nodes = rule.onAllNodesWithText(words, substring = true, useUnmergedTree = true).fetchSemanticsNodes()
        val view = list().fetchSemanticsNode().boundsInRoot
        return nodes.any { it.boundsInRoot.top >= view.top && it.boundsInRoot.bottom <= view.bottom && it.boundsInRoot.height > 0f }
    }

    private fun send() {
        rule.onAllNodes(hasSetTextAction() and !hasTestTag("quote-part"))[0].performTextInput(sent)
        rule.mainClock.advanceTimeBy(300)
        rule.onNodeWithContentDescription("Send").performClick()
        // Long enough for the scroll to have run if it were going to, short of the fake's answer (300 ms + 1.5 s).
        rule.mainClock.advanceTimeBy(1000)
    }

    private fun shot(name: String) = rule.onRoot().captureRoboImage("build/shots/$name.png")

    /** The topmost "Line N" shown whole in the list: the message he is reading. */
    private fun reading(): String {
        val view = list().fetchSemanticsNode().boundsInRoot
        return rule.onAllNodesWithText("Line ", substring = true, useUnmergedTree = true).fetchSemanticsNodes()
            .filter { it.boundsInRoot.top >= view.top && it.boundsInRoot.bottom <= view.bottom && it.boundsInRoot.height > 0f }
            .minBy { it.boundsInRoot.top }
            .config[SemanticsProperties.Text].joinToString("") { it.text }
            .substringBefore(",")
    }

    private fun staysWhileReading(name: String) {
        // Up a screen and a half, by his finger: older lines come down into view.
        repeat(2) {
            list().performTouchInput { swipeDown(startY = top + height * 0.2f, endY = top + height * 0.9f, durationMillis = 300) }
            rule.mainClock.advanceTimeBy(1000)
        }
        val on = reading()
        val before = bounds(on)
        shot("$name-before")
        send()
        shot(name)
        assertEquals("$on stays where it was", before.top, bounds(on).top, 1f)
        assertEquals(on, reading())
        assertFalse("not pulled down to his message", shown(sent))
        assertTrue("his own message is no new answer", rule.onAllNodesWithText("New answer below").fetchSemanticsNodes().isEmpty())
    }

    private fun followsAtTheNewest() {
        assertTrue("starts at the newest", shown("Line 30 from Jarvis"))
        send()
        assertTrue("his message comes into view", shown(sent))
    }

    @Test fun threadStaysWhereHeReads() {
        thread()
        staysWhileReading("send-stays")
    }

    @Test fun threadFollowsAtTheNewest() {
        thread()
        followsAtTheNewest()
    }

    @Test fun mainChatStaysWhereHeReads() {
        main()
        staysWhileReading("send-stays-main")
    }

    @Test fun mainChatFollowsAtTheNewest() {
        main()
        followsAtTheNewest()
        shot("send-stays-main-newest")
    }
}
