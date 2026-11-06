package com.kivan.jarvis.tabs

import android.app.Application
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performTouchInput
import androidx.test.core.app.ApplicationProvider
import com.kivan.jarvis.forum.ForumActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** t251: a link in a question card's words opens like a tab message's, and the rest of the card still opens the card. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w412dp-h915dp-xxhdpi", application = Application::class)
class CardLinkTest {
    @get:Rule val rule = createComposeRule()

    private val page = "http://jarvis.localhost:8080/jarvis-pages/2-0-cards.html"
    private val opened = ArrayList<String>()
    private var cardOpened = 0

    private fun card(text: String, find: String, end: Boolean = false) {
        val handler = object : UriHandler {
            override fun openUri(uri: String) { opened += uri }
        }
        rule.setContent {
            TabsTheme(dark = true) {
                CompositionLocalProvider(LocalUriHandler provides handler) {
                    AskCard(CardView(listOf(CardTab("t9", "Tab", "q1")), text), "", {}, { cardOpened++ }, {})
                }
            }
        }
        rule.onAllNodesWithText(find, substring = true, useUnmergedTree = true)[0].performTouchInput {
            click(if (end) Offset(width / 2f, height - 8f) else Offset(8f, 8f))
        }
        rule.waitForIdle()
    }

    private fun startedPage(): String? {
        val intent = shadowOf(ApplicationProvider.getApplicationContext<Application>()).nextStartedActivity ?: return null
        assertEquals(ForumActivity::class.java.name, intent.component?.className)
        return intent.dataString
    }

    @Test fun onlyLinksChangeAndTheOtherMarksStayAsWritten() {
        assertEquals(
            listOf(Span("**see** "), Span("cards", Kind.Link, page), Span(" or "), Span(page, Kind.Link, page), Span(".")),
            Prose.links("**see** [cards]($page) or $page."),
        )
    }

    @Test fun aBareJarvisAddressOpensInTheApp() {
        card("$page has the three cards, which one?", "has the three cards")
        assertEquals(page, startedPage())
        assertEquals(emptyList<String>(), opened)
        assertEquals(0, cardOpened)
    }

    @Test fun aLabelledJarvisLinkOpensInTheApp() {
        card("[the cards page]($page) has three, which one?", "the cards page")
        assertEquals(page, startedPage())
        assertEquals(0, cardOpened)
    }

    @Test fun anyOtherLinkGoesToTheBrowser() {
        card("[a page elsewhere](https://example.com/a) says yes, agree?", "a page elsewhere")
        assertEquals(listOf("https://example.com/a"), opened)
        assertNull(startedPage())
        assertEquals(0, cardOpened)
    }

    @Test fun aTapOffTheLinkStillOpensTheCard() {
        card("[the cards page]($page) and then many more plain words that fill the line", "plain words", end = true)
        assertEquals(1, cardOpened)
        assertNull(startedPage())
        assertEquals(emptyList<String>(), opened)
    }
}
