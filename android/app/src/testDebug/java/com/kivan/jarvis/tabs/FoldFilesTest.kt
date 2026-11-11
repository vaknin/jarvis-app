package com.kivan.jarvis.tabs

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
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
import java.io.File

/**
 * Pictures and files in the phone's main chat, as a thread draws them: one he sends shows in his own message at once,
 * a picture with no words is just the picture, and Jarvis's files sit under his reply. Pictures land in build/shots.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w412dp-h915dp-xxhdpi", application = android.app.Application::class)
class FoldFilesTest {
    @get:Rule val rule = createComposeRule()

    private val name = "Screenshot_20261108_0912.png"

    /** A phone screenshot's shape, drawn so the shot shows it plainly: a dark page with a few light bars. */
    private fun picture(): Attached {
        val dir = File(ApplicationProvider.getApplicationContext<android.app.Application>().cacheDir, "tray").apply { mkdirs() }
        val file = File(dir, name)
        val bitmap = Bitmap.createBitmap(540, 1170, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(0xFF1E2A3A.toInt())
        val paint = Paint().apply { color = 0xFF8FB8DE.toInt() }
        for (i in 0 until 6) canvas.drawRoundRect(40f, 120f + i * 170f, 500f - (i % 3) * 90f, 220f + i * 170f, 24f, 24f, paint)
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return Attached(file.path, name, true)
    }

    private fun open(attached: Boolean = true): FakeDeck {
        val base = Fake.foldTalk()
        val main = base.tabs.getValue("t0")
        // Jarvis's reply with a file of his, as a thread shows it.
        val reply = Entry.Answer("a-file", 70, "Here is the plan as a file.", files = listOf("voice-plan.pdf"), at = main.entries.maxOf { (it as? Entry.Answer)?.at ?: (it as? Entry.Yours)?.at ?: 0 } + 1)
        val deck = FakeDeck(base.copy(attached = if (attached) listOf(picture()) else emptyList(), tabs = base.tabs + ("t0" to main.copy(entries = main.entries + reply))))
        rule.mainClock.autoAdvance = false
        rule.setContent {
            TabsTheme(dark = true) {
                Box(Modifier.fillMaxSize().background(look.canvas).padding(top = 40.dp, bottom = 12.dp)) { Stage(deck, Opening(fold = true)) }
            }
        }
        rule.mainClock.advanceTimeBy(1200)
        return deck
    }


    /** Wholly inside the main chat's list, not just partly: assertIsDisplayed passes on a sliver. */
    private fun within(node: SemanticsNodeInteraction): Boolean {
        val list = rule.onNodeWithTag("fold-home").getBoundsInRoot()
        val b = node.getUnclippedBoundsInRoot()
        return b.top >= list.top && b.bottom <= list.bottom && b.left >= list.left && b.right <= list.right
    }

    private fun assertWithinList(what: String, node: SemanticsNodeInteraction) {
        val list = rule.onNodeWithTag("fold-home").getBoundsInRoot()
        assertTrue("$what wholly in the list: ${node.getUnclippedBoundsInRoot()} in $list", within(node))
    }

    /** One of his messages: the first node with these words that isn't the box. */
    private fun his(words: String) = rule.onAllNodes(hasText(words) and !hasSetTextAction())[0]

    /** Sends the attached picture with no words, and waits for it to draw (it's read off the main thread). */
    private fun sendPicture(deck: FakeDeck) {
        rule.onNodeWithContentDescription("Send").performClick()
        rule.mainClock.advanceTimeBy(300)
        assertEquals(listOf(name), deck.state.value.tabs.getValue("t0").entries.filterIsInstance<Entry.Yours>().last().files)
        // The page never idles (its work line moves), so the clock is pumped by hand while the picture is read.
        val drawn = { rule.onAllNodes(hasContentDescription(name)).fetchSemanticsNodes().size == 1 }
        val until = System.currentTimeMillis() + 5_000
        while (!drawn() && System.currentTimeMillis() < until) {
            rule.mainClock.advanceTimeBy(50)
            Thread.sleep(20)
        }
        assertEquals("his picture drawn in his message", true, drawn())
        // On screen, not just in the list: the chat brings his new message into view.
        rule.mainClock.advanceTimeBy(300)
        rule.onNodeWithContentDescription(name).assertIsDisplayed()
    }

    @Test
    fun mainChatShowsSentPicture() {
        val deck = open()
        // Jarvis's file under his reply.
        rule.onNodeWithText("voice-plan.pdf").assertExists()
        sendPicture(deck)
        // His message is just the picture: no words, no empty panel around it.
        val his = rule.onNode(hasTestTag("said") and hasAnyDescendant(hasContentDescription(name)))
        his.assertExists()
        val words = rule.onAllNodes(hasTestTag("said") and hasAnyDescendant(hasContentDescription(name)) and hasAnyDescendant(SemanticsMatcher.keyIsDefined(SemanticsProperties.Text)))
        assertEquals(0, words.fetchSemanticsNodes().size)
    }

    @Test
    fun mainChatFollowsHisMessage() {
        val deck = open(attached = false)
        rule.onAllNodes(hasSetTextAction())[0].performTextInput("on its way")
        rule.mainClock.advanceTimeBy(300)
        rule.onNodeWithContentDescription("Send").performClick()
        rule.mainClock.advanceTimeBy(600)
        assertWithinList("his words", his("on its way"))
        deck.attach(picture())
        rule.mainClock.advanceTimeBy(300)
        sendPicture(deck)
        assertWithinList("his picture", rule.onNodeWithContentDescription(name))
        // Scrolled up to read, a message of his that comes in without a Send here leaves him where he reads.
        repeat(4) {
            rule.onNodeWithTag("fold-home").performTouchInput { swipeDown() }
            rule.mainClock.advanceTimeBy(600)
        }
        val up = rule.onAllNodes(hasContentDescription(name)).fetchSemanticsNodes().isEmpty() || !within(rule.onNodeWithContentDescription(name))
        assertTrue("scrolled up, away from his picture", up)
        deck.hold("t0", "came back on reload", emptyList())
        rule.mainClock.advanceTimeBy(600)
        val shown = rule.onAllNodesWithText("came back on reload").fetchSemanticsNodes().isNotEmpty() && within(his("came back on reload"))
        assertFalse("not pulled back to the bottom", shown)
    }

    @Test
    fun screens() {
        val deck = open()
        sendPicture(deck)
        rule.onNodeWithContentDescription(name).assertExists()
        rule.onNodeWithTag("fold-home").performScrollToIndex(0)
        rule.mainClock.advanceTimeBy(600)
        assertWithinList("his picture", rule.onNodeWithContentDescription(name))
        rule.onRoot().captureRoboImage("build/shots/fold-sent-picture.png")
    }
}
