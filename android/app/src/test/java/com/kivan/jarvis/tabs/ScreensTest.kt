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
import com.github.takahirom.roborazzi.captureRoboImage
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

    private fun wall(n: Int, name: String, dark: Boolean = true) = shot(name, dark) { Stage(FakeDeck(Fake.deck(n))) }

    @Test fun wall3() = wall(3, "wall-03")
    @Test fun wall5() = wall(5, "wall-05")
    @Test fun wall9() = wall(9, "wall-09")
    @Test fun wall14() = wall(14, "wall-14")
    @Test fun wallEmpty() = wall(0, "wall-00")
    @Test fun wallLight() = wall(9, "wall-09-light", dark = false)
    @Test fun tab() = shot("tab") { Stage(FakeDeck(Fake.deck(9)), Opening(tab = "t1")) }
    @Test fun tabQuestion() = shot("tab-question") { Stage(FakeDeck(Fake.deck(9)), Opening(tab = "t2")) }
    @Test fun tabLight() = shot("tab-light", dark = false) { Stage(FakeDeck(Fake.deck(9)), Opening(tab = "t1")) }
    @Test fun zoom() = shot("zoom-50") { Stage(FakeDeck(Fake.deck(9)), Opening(tab = "t1", zoom = 0.5f)) }
    @Test fun closed() = shot("closed") { Stage(FakeDeck(Fake.deck(9)), Opening(closed = true)) }
    @Test fun reply() = shot("reply") { Stage(FakeDeck(Fake.deck(9)), Opening(tab = "t1", reply = "a1")) }
    @Test fun replyDraft() = shot("reply-draft") {
        Stage(FakeDeck(Fake.deck(9, drafts = mapOf("t1" to Fake.draft))), Opening(tab = "t1", reply = "a1"))
    }
    @Test fun replyWriting() {
        shot("reply-quote", capture = { rule.onNodeWithTag("reply").performScrollToIndex(9); rule.mainClock.advanceTimeBy(600) }) {
            Stage(FakeDeck(Fake.deck(9, drafts = mapOf("t1" to Fake.draft))), Opening(tab = "t1", reply = "a1"))
        }
    }

    @Test fun muted() = shot("wall-muted") { Stage(FakeDeck(Fake.deck(9, muted = true))) }
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

    @Test fun bigWall() = shot("font200-wall-14", scale = 2f) { Stage(FakeDeck(Fake.deck(14))) }
    @Test fun bigTab() = shot("font200-tab", scale = 2f) { Stage(FakeDeck(Fake.deck(9)), Opening(tab = "t1")) }
}
