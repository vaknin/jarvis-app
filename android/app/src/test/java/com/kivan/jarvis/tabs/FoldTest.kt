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

    // The move key (stage 5e). The main chat's talk, two subjects interleaved: Voice (10, 50), the phone's dark mode (30).
    private val talk = listOf(
        Entry.Yours("m1", "the voice call cuts out after a minute", offset = 10, at = 1000),
        Entry.Answer("m2", 20, "The call's buffer fills up and drops.", at = 1001),
        Entry.Yours("m3", "also, the phone app's dark mode is too grey", offset = 30, at = 1002),
        Entry.Answer("m4", 40, "Dark mode gets a deeper black.", at = 1003),
        Entry.Yours("m5", "and the voice should sound calmer", offset = 50, at = 1004),
        Entry.Answer("m6", 60, "A calmer voice, slower and lower.", at = 1005),
    )

    private fun talking(moving: Moving? = null): DeckState {
        val main = TileView("t0", 3, MAIN_NAME)
        return DeckState(tiles = listOf(tile("t1"), main, tile("t2")), tabs = mapOf("t0" to TabView(main, talk)), moving = moving)
    }

    @Test fun theMainChatIsTheServersElseTheTabCalledJarvisNeverTheFirstAndIsNoThread() {
        val s = talking()
        assertEquals("t0", mainChat(s.tiles))
        assertEquals(listOf("t1", "t2"), foldThreads(s.tiles).map { it.id })
        // Task 943: his first tab, a subject's, is never taken for it.
        assertNull(mainChat(listOf(TileView("b", 2, "B"), TileView("a", 1, "IMAGES BOOK"))))
        assertEquals("j", mainChat(listOf(TileView("a", 1, "IMAGES BOOK"), TileView("j", 5, " jarvis "))))
        // The server's word wins over a name.
        assertEquals("h", mainChat(listOf(TileView("j", 1, MAIN_NAME), TileView("h", 3, "Home", main = true))))
        assertNull(mainChat(emptyList()))
    }

    @Test fun theMainChatsTalkSitsAmongReportsByTimeAndWhatsOnItsWayIsNewest() {
        val pending = Entry.Yours("p1", "one more", state = Sending.Sending)
        val main = TileView("t0", 1, MAIN_NAME)
        val s = DeckState(
            tiles = listOf(main, tile("t1")),
            tabs = mapOf("t0" to TabView(main, talk.take(2) + pending)),
            digest = listOf(DigestView("t1", "One", 1000L + 0, 1, "done", "between")),
        )
        // The report at 1000 and his message at 1000 keep the order they came in: reports first, then the talk.
        assertEquals(listOf("r/t1", "m/m1", "m/m2", "m/p1"), foldLines(s).map { it.key })
    }

    @Test fun onlyTheServersOffsetsLightUpAndASubjectSaidBetweenStaysDark() {
        val m = Moving("t0", 50, "Voice", listOf(10, 20, 50, 60))
        val lines = foldLines(talking(m))
        assertEquals(setOf("m/m1", "m/m2", "m/m5", "m/m6"), lit(lines, m))
        // Never guessed from order: what the server names is all that lights, even two lines apart.
        assertEquals(setOf("m/m1", "m/m6"), lit(lines, m.copy(offsets = listOf(10, 60))))
        // Still asking: nothing lights.
        assertEquals(emptySet<String>(), lit(lines, Moving("t0", 50)))
        assertEquals("Move 4 messages on Voice into a new thread?", moveAsk(m))
        assertEquals("Move 1 message on Voice into a new thread?", moveAsk(m.copy(offsets = listOf(10))))
    }

    @Test fun theServersAnswerLightsUpOrSaysWhyAndAStaleOneChangesNothing() {
        val asked = Moving("t0", 50)
        val s = talking(asked)
        val lit = s.subjected(asked, "Voice", listOf(10, 20, 50, 60), "")
        assertTrue(lit.moving!!.lit)
        assertEquals(listOf(10L, 20L, 50L, 60L), lit.moving!!.offsets)
        val no = s.subjected(asked, "", emptyList(), "moving a subject to a thread works in the main chat, with the library fold on")
        assertNull(no.moving)
        assertEquals("moving a subject to a thread works in the main chat, with the library fold on", no.notice!!.text)
        assertEquals("Nothing here has a subject to move yet.", s.subjected(asked, "", emptyList(), "").notice!!.text)
        // He kept them, or pressed again elsewhere, before the answer came: it changes nothing.
        val kept = s.copy(moving = null)
        assertEquals(kept, kept.subjected(asked, "Voice", listOf(10), ""))
    }

    @Test fun aMoveEndsLitEitherWayAndSaysWhere() {
        val s = talking(Moving("t0", 50, "Voice", listOf(10, 20, 50, 60), sent = true))
        val done = s.split("t0", "Voice", 4, "")
        assertNull(done.moving)
        assertEquals("4 messages moved into the thread \"Voice\".", done.notice!!.text)
        val refused = s.split("t0", "", 0, "the subject changed since it lit up; press the key again")
        assertNull(refused.moving)
        assertEquals("the subject changed since it lit up; press the key again", refused.notice!!.text)
    }
}
