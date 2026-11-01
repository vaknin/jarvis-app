package com.kivan.jarvis.tabs

import android.app.Application
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
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

/** A real tap on a real link, under what the tab and reply pages wrap it in. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w412dp-h915dp-xxhdpi", application = Application::class)
class ProseLinkTest {
    @get:Rule val rule = createComposeRule()

    private val opened = ArrayList<String>()
    private var wrote = 0

    private fun tap(text: String) {
        val handler = object : UriHandler {
            override fun openUri(uri: String) { opened += uri }
        }
        rule.setContent {
            TabsTheme(dark = true) {
                CompositionLocalProvider(LocalUriHandler provides handler) {
                    Box(Modifier.clickable { wrote++ }) { SelectionContainer { ProseView(Prose.parse(text)) } }
                }
            }
        }
        rule.onRoot().performTouchInput { click(Offset(12f, 12f)) }
        rule.waitForIdle()
    }

    @Test
    fun aJarvisPageOpensInTheAppNotTheBrowser() {
        tap("[this page and more words](http://jarvis.localhost:8080/jarvis-pages/wall-design.html?a=1#b)")
        val app = ApplicationProvider.getApplicationContext<Application>()
        val intent = shadowOf(app).nextStartedActivity
        assertEquals(ForumActivity::class.java.name, intent.component?.className)
        assertEquals("http://jarvis.localhost:8080/jarvis-pages/wall-design.html?a=1#b", intent.dataString)
        assertEquals(emptyList<String>(), opened)
        assertEquals(0, wrote)
    }

    @Test
    fun anyOtherLinkGoesToTheBrowser() {
        tap("[a page elsewhere and more words](https://example.com/a)")
        assertEquals(listOf("https://example.com/a"), opened)
        assertNull(shadowOf(ApplicationProvider.getApplicationContext<Application>()).nextStartedActivity)
        assertEquals(0, wrote)
    }
}
