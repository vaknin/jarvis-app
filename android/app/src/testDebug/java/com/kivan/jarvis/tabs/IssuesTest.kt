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

    @Test fun theBarIsOneSegmentPerIssueFilledByItsOwnStep() {
        val bar = issuesBar(Fake.issues)!!
        assertEquals(listOf(39L, 40L, 41L, 42L, 43L), bar.segments.map { it.id })
        assertEquals(listOf(4, 4, 2, 1, 0), bar.segments.map { it.step })
        assertEquals("2 of 5 done · held", bar.words)
        assertNull("no issues, no bar", issuesBar(emptyList()))
        assertEquals("done today still shows", "1 of 1 done", issuesBar(listOf(IssueView(1, "x", "b", 4)))!!.words)
        assertEquals("0 of 1 done · held", issuesBar(listOf(IssueView(1, "x", "b", 0, held = true)))!!.words)
    }

    @Test fun pastEightTheBarKeepsTheMostRecentOpenOnesAndTheWordsCountAll() {
        val many = (1L..10L).map { IssueView(it, "issue $it", "b", if (it <= 2) 4 else 1) }
        val bar = issuesBar(many)!!
        assertEquals(MAX_SEGMENTS, bar.segments.size)
        // The eight most recent open ones, in filing order; the done ones go first.
        assertEquals((3L..10L).toList(), bar.segments.map { it.id })
        assertEquals("2 of 10 done", bar.words)
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
