package com.kivan.jarvis.tabs

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The two buttons: the mic writes, Call calls, and nothing but a press does either. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w412dp-h915dp-xxhdpi", application = android.app.Application::class)
class TalkTest {
    @get:Rule val rule = createComposeRule()

    private fun stage(deck: FakeDeck, opening: Opening = Opening()) {
        rule.mainClock.autoAdvance = false
        rule.setContent { TabsTheme { Stage(deck, opening) } }
        rule.mainClock.advanceTimeBy(1200)
    }

    private fun press(label: String) {
        // The next tab's page sits beside this one, with the same buttons: the first is the one on screen.
        rule.onAllNodesWithContentDescription(label)[0].performClick()
        rule.mainClock.advanceTimeBy(300)
    }

    @Test fun nothingListensUntilHePresses() {
        val deck = FakeDeck(Fake.deck(3))
        stage(deck)
        assertFalse(deck.state.value.dictating)
        assertTrue(deck.calls.isEmpty())
    }

    @Test fun callOnTheWallIsANewTab() {
        val deck = FakeDeck(Fake.deck(3))
        stage(deck)
        press("Call Jarvis here")
        assertEquals(listOf<String?>(null), deck.calls)
    }

    @Test fun callInATabIsThatTab() {
        val deck = FakeDeck(Fake.deck(3))
        stage(deck, Opening(tab = "t1"))
        press("Call Jarvis here")
        assertEquals(listOf<String?>("t1"), deck.calls)
    }

    @Test fun theMicStartsAndStops() {
        val deck = FakeDeck(Fake.deck(3))
        stage(deck, Opening(tab = "t1"))
        press("Dictate")
        assertTrue(deck.state.value.dictating)
        press("Stop dictating")
        assertFalse(deck.state.value.dictating)
    }

    @Test fun aCallHasTheMic() {
        val deck = FakeDeck(Fake.deck(3).copy(call = OnCall("t1", "Tab 1", "Listening")))
        stage(deck, Opening(tab = "t1"))
        rule.onAllNodesWithContentDescription("Back to the call")[0].assertIsDisplayed()
        assertEquals("the mic button is gone", 0, rule.onAllNodesWithContentDescription("Dictate").fetchSemanticsNodes().size)
    }

    @Test fun aFileAloneCanBeSent() {
        val deck = FakeDeck(Fake.deck(3).copy(attached = listOf(Attached("/nowhere/a.pdf", "a.pdf", false))))
        stage(deck, Opening(tab = "t1"))
        press("Send")
        assertTrue(deck.state.value.attached.isEmpty())
    }
}
