package com.kivan.jarvis.tabs

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Jarvis 2.0 on the phone: the bar, the overview sheet and the Done line show only when the server sends issues. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w412dp-h915dp-xxhdpi", application = android.app.Application::class)
class IssuesTest {
    @get:Rule val rule = createComposeRule()

    private fun stage(v2: Boolean) {
        rule.mainClock.autoAdvance = false
        rule.setContent { TabsTheme { Stage(FakeDeck(Fake.deck(9, v2 = v2)), Opening(tab = "t3")) } }
        rule.mainClock.advanceTimeBy(1200)
    }

    @Test fun theBarIsTheAverageStepOfTheOpenOnes() {
        val bar = issuesBar(Fake.issues)!!
        // Testing (2), Building (1), Planning (0); the done one doesn't count.
        assertEquals(3f / 12f, bar.done, 0.001f)
        assertEquals("3 issues · held", bar.words)
        assertNull("nothing open, no bar", issuesBar(Fake.issues.filter { !it.open }))
        assertEquals("1 issue", issuesBar(listOf(IssueView(1, "x", "b", 3)))!!.words)
    }

    @Test fun whereSaysTheTryFromTwoOn() {
        assertEquals("Testing · try 2", IssueView(1, "x", "b", 2, 2).where())
        assertEquals("Building", IssueView(1, "x", "b", 1, 1).where())
        assertEquals("Waiting on you", IssueView(1, "x", "b", 1, label = "Waiting on you").where())
    }

    @Test fun onlyTwoPointOhsDoneLineIsStyled() {
        assertTrue(isDoneLine(Fake.DONE))
        assertFalse("today's line stays as it is", isDoneLine("Done: Dark mode on the piano app"))
    }

    @Test fun withoutIssuesTheTabLooksAsToday() {
        stage(v2 = false)
        assertEquals(1, rule.onAllNodesWithTag("jobs").fetchSemanticsNodes().size)
        assertEquals(0, rule.onAllNodesWithTag("issues").fetchSemanticsNodes().size)
    }

    @Test fun theBarOpensTheOverviewAndARowItsDetail() {
        stage(v2 = true)
        assertEquals(0, rule.onAllNodesWithTag("jobs").fetchSemanticsNodes().size)
        rule.onAllNodesWithTag("issues")[0].performClick()
        rule.mainClock.advanceTimeBy(600)
        assertEquals(1, rule.onAllNodesWithText("Issues from Dark mode").fetchSemanticsNodes().size)
        val why = "Held: the usage freeze is on until 11:00 p.m."
        assertEquals("the reason waits for a tap", 0, rule.onAllNodesWithText(why).fetchSemanticsNodes().size)
        rule.onAllNodesWithText("Lesson screen layout for tablets")[0].performClick()
        rule.mainClock.advanceTimeBy(600)
        assertEquals(1, rule.onAllNodesWithText(why).fetchSemanticsNodes().size)
    }
}
