package com.kivan.jarvis.tabs

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.swipeUp
import com.github.takahirom.roborazzi.captureRoboImage
import androidx.compose.ui.test.longClick
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The screens drawn on the JVM at Pixel 8 size. Pictures land in build/shots:
 * `./gradlew :app:recordRoborazziDebug --tests '*ScreensTest*'`.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w412dp-h915dp-xxhdpi", application = android.app.Application::class)
class ScreensTest {
    @get:Rule val rule = createComposeRule()

    private fun shot(name: String, dark: Boolean = true, scale: Float = 1f, capture: () -> Unit = {}, content: @Composable () -> Unit) {
        // The moving line never stops, so the clock is stepped by hand.
        rule.mainClock.autoAdvance = false
        rule.setContent {
            val d = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(d.density, scale)) {
                TabsTheme(dark = dark) {
                    // Robolectric has no system bars: leave the room the phone's would take.
                    Box(Modifier.fillMaxSize().background(look.canvas).padding(top = 40.dp, bottom = 12.dp)) { content() }
                }
            }
        }
        rule.mainClock.advanceTimeBy(1200)
        capture()
        rule.onRoot().captureRoboImage("build/shots/$name.png")
    }

    private fun wall(n: Int, name: String, dark: Boolean = true) = shot(name, dark) { Stage(FakeDeck(Fake.deck(n)), Opening(list = false)) }

    @Test fun wall3() = wall(3, "wall-03")
    @Test fun wall5() = wall(5, "wall-05")
    @Test fun wall9() = wall(9, "wall-09")
    @Test fun wall14() = wall(14, "wall-14")
    @Test fun wallEmpty() = wall(0, "wall-00")
    @Test fun wallLight() = wall(9, "wall-09-light", dark = false)
    @Test fun tab() = shot("tab") { Stage(FakeDeck(Fake.deck(9)), Opening(tab = "t1")) }
    @Test fun tabJobs() = shot("tab-jobs") { Stage(FakeDeck(Fake.deck(9)), Opening(tab = "t3")) }
    // Jarvis 2.0: the issues' bar where the jobs line was, and a Done line.
    @Test fun tabIssues() = shot("tab-issues") { Stage(FakeDeck(Fake.deck(9, v2 = true)), Opening(tab = "t3")) }
    @Test fun tabQuestion() = shot("tab-question") { Stage(FakeDeck(Fake.deck(9)), Opening(tab = "t2")) }
    @Test fun tabLight() = shot("tab-light", dark = false) { Stage(FakeDeck(Fake.deck(9)), Opening(tab = "t1")) }
    @Test fun zoom() = shot("zoom-50") { Stage(FakeDeck(Fake.deck(9)), Opening(tab = "t1", zoom = 0.5f)) }
    // J9: the app opens on Needs you, with Digest beside it.
    @Test fun needs() = shot("needs-you") { Stage(FakeDeck(Fake.deck(9))) }
    @Test fun needsLight() = shot("needs-you-light", dark = false) { Stage(FakeDeck(Fake.deck(9))) }
    @Test fun needsEmpty() = shot("needs-you-empty") { Stage(FakeDeck(Fake.deck(9).copy(cards = emptyList()))) }
    @Test fun digest() = shot("digest") { Stage(FakeDeck(Fake.deck(9)), Opening(digest = true)) }
    // The library fold (behind its switch): the main chat, the jump chip, the thread list, a thread, a new thread.
    @Test fun foldHome() = shot("fold-home") { Stage(FakeDeck(Fake.fold()), Opening(fold = true)) }
    // Newer reports push both cards out of view: the chip counts them, the reports never.
    @Test fun foldChip() = shot("fold-home-chip") { Stage(FakeDeck(Fake.fold().let { s -> s.copy(digest = s.digest + List(20) { DigestView("t${it % 6 + 1}x$it", "Thread ${it + 1}", Long.MAX_VALUE / 2 + it, 1, "done", "Fixed and landed.") }) }), Opening(fold = true)) }
    @Test fun foldThreads() = shot("fold-threads", capture = {
        rule.onAllNodesWithText("Threads")[0].performClick()
        rule.mainClock.advanceTimeBy(600)
    }) { Stage(FakeDeck(Fake.fold()), Opening(fold = true)) }
    @Test fun foldThread() = shot("fold-thread") { Stage(FakeDeck(Fake.fold()), Opening(tab = "t1", fold = true)) }
    @Test fun foldThreadNeeds() = shot("fold-thread-needs") { Stage(FakeDeck(Fake.fold()), Opening(tab = "t2", fold = true)) }
    @Test fun foldHomeLight() = shot("fold-home-light", dark = false) { Stage(FakeDeck(Fake.fold()), Opening(fold = true)) }
    // Fold 5e, the move key: a long-press on "and the voice should sound calmer" offers the move; the server's Voice
    // messages light up (the dark-mode one said between stays dark) and the bar asks; Move puts them in a new thread.
    private fun pressMove() {
        rule.onAllNodesWithText("and the voice should sound calmer")[0].performTouchInput { longClick() }
        rule.mainClock.advanceTimeBy(600)
        rule.onAllNodesWithText("Open thread")[0].performClick()
        rule.mainClock.advanceTimeBy(600)
    }
    // The menu is a popup of its own window, which Robolectric doesn't draw: the test holds that it's offered.
    @Test fun foldMoveMenu() = shot("fold-move-menu", capture = {
        rule.onAllNodesWithText("and the voice should sound calmer")[0].performTouchInput { longClick() }
        rule.mainClock.advanceTimeBy(600)
        rule.onAllNodesWithText("Open thread")[0].assertExists()
    }) { Stage(FakeDeck(Fake.foldTalk()), Opening(fold = true)) }
    @Test fun foldMoveLit() = shot("fold-move-lit", capture = {
        pressMove()
        assertEquals(4, rule.onAllNodesWithTag("lit").fetchSemanticsNodes().size)
        rule.onAllNodesWithText("Move 4 messages on Voice into a new thread?")[0].assertExists()
    }) { Stage(FakeDeck(Fake.foldTalk()), Opening(fold = true)) }
    @Test fun foldMoveLitLight() = shot("fold-move-lit-light", dark = false, capture = { pressMove() }) { Stage(FakeDeck(Fake.foldTalk()), Opening(fold = true)) }
    @Test fun foldMoved() {
        val deck = FakeDeck(Fake.foldTalk())
        shot("fold-moved", capture = {
            pressMove()
            rule.onNodeWithTag("move").performClick()
            rule.mainClock.advanceTimeBy(600)
        }) { Stage(deck, Opening(fold = true)) }
        assertEquals(0, rule.onAllNodesWithTag("lit").fetchSemanticsNodes().size)
        assertEquals("Voice", deck.state.value.tiles.last().name)
        assertEquals("4 messages moved into the thread \"Voice\".", deck.state.value.notice?.text)
    }
    @Test fun foldMoveKept() {
        val deck = FakeDeck(Fake.foldTalk())
        shot("fold-move-kept", capture = {
            pressMove()
            rule.onAllNodesWithText("Keep")[0].performClick()
            rule.mainClock.advanceTimeBy(600)
        }) { Stage(deck, Opening(fold = true)) }
        assertEquals(0, rule.onAllNodesWithTag("lit").fetchSemanticsNodes().size)
        assertEquals(Fake.foldTalk().tiles.size, deck.state.value.tiles.size)
    }
    @Test fun dialog() = shot("ask-dialog") { Stage(FakeDeck(Fake.deck(9)), Opening(asking = Fake.cards[0].key)) }
    @Test fun dialogThree() = shot("ask-dialog-3") { Stage(FakeDeck(Fake.deck(9)), Opening(asking = Fake.cards[2].key)) }
    // t116: an autopilot's intake is one card with all its questions.
    @Test fun needsMulti() = shot("needs-you-multi") { Stage(FakeDeck(Fake.deck(9).copy(cards = listOf(Fake.intake, Fake.cards[1])))) }
    @Test fun needsMultiTapped() = shot(
        "needs-you-multi-tapped",
        capture = {
            rule.onAllNodesWithText("Top bar")[0].performClick()
            rule.onAllNodesWithText("Dim the keys")[0].performClick()
            rule.onAllNodesWithText("Something else")[3].performClick()
            rule.mainClock.advanceTimeBy(300)
            rule.onAllNodes(hasSetTextAction())[0].performTextInput("Ask me only if it's past 10 p.m.")
            rule.onNodeWithTag("needs").performTouchInput { swipeUp() }
            rule.mainClock.advanceTimeBy(1200)
        },
    ) { Stage(FakeDeck(Fake.deck(9).copy(cards = listOf(Fake.intake, Fake.cards[1])))) }
    @Test fun needsMultiLight() = shot("needs-you-multi-light", dark = false) { Stage(FakeDeck(Fake.deck(9).copy(cards = listOf(Fake.intake)))) }
    @Test fun dialogLight() = shot("ask-dialog-light", dark = false) { Stage(FakeDeck(Fake.deck(9)), Opening(asking = Fake.cards[0].key)) }
    @Test fun tabNotice() = shot("tab-auto-refused") {
        Stage(FakeDeck(Fake.deck(9).copy(notice = Notice("t2", "autopilot isn't built yet"))), Opening(tab = "t2"))
    }
    @Test fun closed() = shot("closed") { Stage(FakeDeck(Fake.deck(9)), Opening(closed = true, list = false)) }

    @Test fun marked() = shot("tab-marked") {
        val deck = Fake.deck(9)
        val blocks = Prose.parse(deck.tabs.getValue("t1").entries.filterIsInstance<Entry.Answer>().first().text)
        val marks = listOf(1, 3).map { QuoteMark(41, it, blocks[it].quoted) }
        Stage(FakeDeck(deck.copy(marks = mapOf("t1" to marks + QuoteMark(41, 5, blocks[5].quoted, "Yes, keep it")))), Opening(tab = "t1"))
    }
    @Test fun held() = shot("tab-held") {
        val deck = Fake.deck(9)
        val blocks = Prose.parse(deck.tabs.getValue("t1").entries.filterIsInstance<Entry.Answer>().first().text)
        val held = listOf(1 to "Yes, keep it", 3 to "Not this one", 5 to "Later is fine").map { (i, w) -> QuoteMark(41, i, blocks[i].quoted, w, quiet = true) }
        Stage(FakeDeck(deck.copy(marks = mapOf("t1" to held))), Opening(tab = "t1"))
    }
    @Test fun replying() = shot(
        "tab-replying",
        capture = {
            rule.onAllNodesWithTag("paragraph")[0].performTouchInput {
                down(center)
                repeat(6) {
                    advanceEventTime(16)
                    moveBy(Offset(70f, 0f))
                }
                up()
            }
            rule.mainClock.advanceTimeBy(400)
            rule.onAllNodes(hasSetTextAction())[0].performTextInput("Yes, leave it for now")
            rule.mainClock.advanceTimeBy(300)
        },
    ) { Stage(FakeDeck(Fake.deck(9)), Opening(tab = "t1")) }

    @Test fun quotedSent() = shot("tab-quoted") {
        val deck = Fake.deck(9)
        val t3 = deck.tabs.getValue("t3")
        val sent = Entry.Yours("y2", "", quotes = listOf(Quoted(6, "Both are running side by side. I'll check them and tell you.", "same")))
        Stage(FakeDeck(deck.copy(tabs = deck.tabs + ("t3" to t3.copy(entries = t3.entries + sent)))), Opening(tab = "t3"))
    }

    private val onCall = OnCall("t1", "Piano app", "Listening")
    private val photo = Attached("/nowhere/kitchen.jpg", "kitchen.jpg", true)
    private val paper = Attached("/nowhere/lease.pdf", "lease.pdf", false)
    @Test fun dictating() = shot("tab-dictating") {
        Stage(FakeDeck(Fake.deck(9).copy(dictating = true, heard = spoken)), Opening(tab = "t1"))
    }
    @Test fun callHere() = shot("tab-on-call") { Stage(FakeDeck(Fake.deck(9).copy(call = onCall)), Opening(tab = "t1")) }
    @Test fun callElsewhere() = shot("tab-call-elsewhere") { Stage(FakeDeck(Fake.deck(9).copy(call = onCall)), Opening(tab = "t2")) }
    @Test fun callWall() = shot("wall-on-call") { Stage(FakeDeck(Fake.deck(9).copy(call = onCall)), Opening(list = false)) }
    @Test fun trayWall() = shot("wall-tray") { Stage(FakeDeck(Fake.deck(9).copy(attached = listOf(photo, paper))), Opening(list = false)) }
    @Test fun trayTab() = shot("tab-tray") { Stage(FakeDeck(Fake.deck(9).copy(attached = listOf(photo))), Opening(tab = "t1")) }
    private val spoken = "Make the piano louder when the room is noisy, and show me the level while it plays"
    @Test fun heardWall() = shot("wall-heard") { Stage(FakeDeck(Fake.deck(9).copy(heard = spoken)), Opening(list = false)) }
    @Test fun heardTab() = shot("tab-heard") { Stage(FakeDeck(Fake.deck(9).copy(heard = spoken)), Opening(tab = "t1")) }
    @Test fun offline() = shot("tab-offline") { Stage(FakeDeck(Fake.deck(9, online = false)), Opening(tab = "t1")) }
    @Test fun readNew() = shot("tab-new-long") { Stage(FakeDeck(Fake.deck(9)), Opening(tab = "t4")) }

    @Config(qualifiers = "w360dp-h640dp-xhdpi")
    @Test fun smallWall() = wall(14, "small-wall-14")

    @Config(qualifiers = "w360dp-h640dp-xhdpi")
    @Test fun smallTab() = shot("small-tab") { Stage(FakeDeck(Fake.deck(9)), Opening(tab = "t1")) }

    @Config(qualifiers = "w840dp-h1180dp-xhdpi")
    @Test fun tabletWall() = wall(9, "tablet-wall-09")

    @Config(qualifiers = "w840dp-h1180dp-xhdpi")
    @Test fun tabletTab() = shot("tablet-tab") { Stage(FakeDeck(Fake.deck(9)), Opening(tab = "t1")) }

    @Test fun bigWall() = shot("font200-wall-14", scale = 2f) { Stage(FakeDeck(Fake.deck(14)), Opening(list = false)) }
    @Test fun bigTab() = shot("font200-tab", scale = 2f) { Stage(FakeDeck(Fake.deck(9)), Opening(tab = "t1")) }
}
