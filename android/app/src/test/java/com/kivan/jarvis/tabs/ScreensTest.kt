package com.kivan.jarvis.tabs

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
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
@Config(sdk = [36], qualifiers = "w412dp-h915dp-xxhdpi")
class ScreensTest {
    @get:Rule val rule = createComposeRule()

    private fun shot(name: String, dark: Boolean = true, content: @Composable () -> Unit) {
        rule.setContent {
            TabsTheme(dark = dark) {
                // Robolectric has no system bars: leave the room the phone's would take.
                Box(Modifier.fillMaxSize().background(look.canvas).padding(top = 40.dp, bottom = 12.dp)) { content() }
            }
        }
        rule.waitForIdle()
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
}
