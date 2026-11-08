package com.kivan.jarvis.tabs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FoldTest {
    private fun tile(id: String, needs: Boolean = false, working: Boolean = false, unread: Boolean = false) =
        TileView(id, 1, id, needs = needs, working = working, unread = unread)

    private fun issue(id: Long, step: Int, label: String = "") = IssueView(id, "i$id", "Piano", step, label = label)

    @Test fun stepsDoneRunningThenToCome() {
        val s = threadSteps(tile("a", working = true), listOf(issue(1, 4), issue(2, 2), issue(3, 0)))
        assertEquals(listOf(StepState.Done, StepState.Running, StepState.Todo), s.steps)
        assertEquals("1 of 3 done", s.words)
    }

    @Test fun waitingOnHimIsOrangeAndSaysSo() {
        val s = threadSteps(tile("a", needs = true, working = true), listOf(issue(1, 4), issue(2, 1)))
        assertEquals(listOf(StepState.Done, StepState.Needs), s.steps)
        assertEquals("needs you", s.words)
        assertTrue(s.needs)
    }

    @Test fun noIssuesNoBarOnlyWords() {
        assertEquals(ThreadSteps(emptyList(), "working", false), threadSteps(tile("a", working = true), emptyList()))
        assertEquals("", threadSteps(tile("a"), emptyList()).words)
    }

    @Test fun theLineAboveTheBoxTakesTheStepsColours() {
        val issues = listOf(issue(1, 4), issue(2, 2))
        assertEquals(StepState.Running, threadNow(tile("a", working = true), issues))
        assertEquals(StepState.Needs, threadNow(tile("a", needs = true, working = true), issues))
        assertEquals(StepState.Done, threadNow(tile("a"), listOf(issue(1, 4))))
    }

    @Test fun phaseAndBoard() {
        val issues = listOf(issue(1, 4), issue(2, 2, "Testing"))
        assertEquals("Testing", threadPhase(tile("a", working = true), issues))
        assertEquals("Done", threadPhase(tile("a"), listOf(issue(1, 4))))
        assertEquals("needs you", threadPhase(tile("a", needs = true), issues))
        assertEquals("Piano", threadBoard(issues))
    }

    @Test fun mainChatKeepsOneReportPerThreadAndEveryCard() {
        val state = DeckState(
            tiles = listOf(tile("t1", unread = true), tile("t2")),
            digest = listOf(
                DigestView("t1", "One", 10, 1, "done", "old"),
                DigestView("t1", "One", 30, 1, "report", "new"),
                DigestView("t2", "Two", 20, 1, "default", "taken by Jarvis"),
            ),
            cards = listOf(CardView(listOf(CardTab("t2", "Two", "q1")), "Which?", at = 25)),
        )
        val lines = foldLines(state)
        assertEquals(listOf("c/t2/q1", "r/t1"), lines.map { it.key })
        val r = lines.last() as FoldLine.Report
        assertEquals("new", r.line)
        assertTrue(r.unread)
    }

    @Test fun chipCountsOnlyQuestions() {
        assertNull(chipWords(0))
        assertEquals("1 question", chipWords(1))
        assertEquals("2 questions", chipWords(2))
    }

    @Test fun workingLine() {
        assertNull(workingLine(DeckState(tiles = listOf(tile("a")))))
        assertEquals("2 working", workingLine(DeckState(tiles = listOf(tile("a", working = true), tile("b", working = true)))))
    }
}
