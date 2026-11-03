package com.kivan.jarvis.tabs

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Auto before a first word (t67): "I cannot enable it on a new tab currently, must send a message first". */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w412dp-h915dp-xxhdpi", application = android.app.Application::class)
class AutoNewTabTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun autoInTheNewTabBoxOpensATabWithAutopilotOn() {
        val deck = FakeDeck(Fake.deck(9))
        rule.mainClock.autoAdvance = false
        rule.setContent { TabsTheme(dark = true) { Stage(deck, Opening(list = false)) } }
        rule.mainClock.advanceTimeBy(1200)
        val before = deck.state.value.tiles.size
        rule.onNodeWithContentDescription("Autopilot off").performClick()
        rule.mainClock.advanceTimeBy(1500)
        val tiles = deck.state.value.tiles
        assertEquals(before + 1, tiles.size)
        assertTrue("the server's state: on", tiles.last().autopilot)
        assertTrue("no words of his went", deck.state.value.tabs.getValue(tiles.last().id).entries.isEmpty())
        // In the tab now, its Auto reads what the server says.
        assertTrue(rule.onAllNodesWithContentDescription("Back to the wall").fetchSemanticsNodes().isNotEmpty())
        assertEquals(1, rule.onAllNodesWithContentDescription("Autopilot on").fetchSemanticsNodes().size)
    }
}
