package com.kivan.tether

import com.kivan.tether.CallText.Item
import com.kivan.tether.CallText.Para
import com.kivan.tether.CallText.Span
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class CallTextTest {
    /** Real lines from calls; `CALL_LINES=file` runs the checks on every line in it as well. */
    private val lines: List<String> =
        javaClass.getResourceAsStream("/call-lines.txt")!!.bufferedReader().readLines().filter { it.isNotBlank() } +
            System.getenv("CALL_LINES")?.let { File(it).readLines().filter(String::isNotBlank) }.orEmpty()

    private fun squeezed(s: String) = s.filterNot(Char::isWhitespace)

    private fun boldOf(line: String) = CallText.blocks(line).flatMap { it.spans }.filter { it.bold }.map { it.text.trim() }

    @Test
    fun everyWordStaysInOrder() {
        assertTrue(lines.size >= 20)
        for (l in lines) for (mine in listOf(false, true)) {
            assertEquals(squeezed(l), squeezed(CallText.plain(CallText.blocks(l, mine))))
        }
    }

    @Test
    fun aGrowingLineKeepsWhatIsOnTheScreen() {
        for (l in lines) {
            val all = CallText.sentences(l)
            for (n in 1 until all.size) {
                val before = shape(all.take(n).joinToString(" "))
                val after = shape(all.take(n + 1).joinToString(" "))
                // "First" indents when "Second" arrives, and only then.
                if (all.take(n + 1).any { it.startsWith("Second") || it.startsWith("Two") }) continue
                assertEquals(l, before.dropLast(1), after.take(before.size - 1))
                assertTrue(l, after[before.size - 1].startsWith(before.last()))
            }
        }
    }

    /** Each block's words, weight left out. */
    private fun shape(line: String) = CallText.blocks(line).map { b -> b.spans.joinToString("") { it.text } }

    @Test
    fun aShortLineStaysAsItIs() {
        val l = "Fair, though I checked it twice. It's on your phone."
        assertEquals(listOf(Para(listOf(Span(l)))), CallText.blocks(l))
    }

    @Test
    fun theFirstSentenceIsTheHeadline() =
        assertEquals(
            listOf("Long calls don't degrade it."),
            boldOf("Long calls don't degrade it. Street noise does. I'd keep the fix."),
        )

    @Test
    fun aGreetingOrAStallIsNoHeadline() {
        assertEquals(listOf("Names beat numbers."), boldOf("Agreed. Names beat numbers. Here's how."))
        assertEquals(listOf("It ran at midnight."), boldOf("Let me pull that up. It ran at midnight. It read 14 calls."))
    }

    @Test
    fun turnWordsAndClosingQuestionsOpenAParagraph() {
        val blocks = CallText.blocks("It works. It's on. It's fast. But the build is slow. Want me to fix it?")
        assertEquals(
            listOf("It works. It's on. It's fast.", "But the build is slow.", "Want me to fix it?"),
            shape("It works. It's on. It's fast. But the build is slow. Want me to fix it?"),
        )
        assertTrue(blocks.all { it is Para })
    }

    @Test
    fun aRunSaidInOrderIsNumbered() {
        val blocks = CallText.blocks("Two weak spots. First, numbers get misheard. Names work better. Second, a dead terminal stays. Okay?")
        assertEquals("Two weak spots.", (blocks[0] as Para).spans.single().text)
        assertEquals(Item("1.", false, listOf(Span("First, numbers get misheard. Names work better."))), blocks[1])
        assertEquals(Item("2.", false, listOf(Span("Second, a dead terminal stays."))), blocks[2])
        assertEquals("Okay?", (blocks[3] as Para).spans.single().text)
    }

    @Test
    fun aLoneFirstIsNoList() =
        assertTrue(CallText.blocks("Thirty things. First, the call drops thread. It's live.").all { it is Para })

    @Test
    fun aCountedListKeepsItsCommasAndAnd() =
        assertEquals(
            listOf(
                Para(listOf(Span("Three things:", bold = true))),
                Item("•", false, listOf(Span("the notify bug,"))),
                Item("•", false, listOf(Span("the update on 178,"))),
                Item("•", false, listOf(Span("and the mic thread."))),
                Para(listOf(Span("It's on my list. It can't drop."))),
            ),
            CallText.blocks("Three things: the notify bug, the update on 178, and the mic thread. It's on my list. It can't drop."),
        )

    @Test
    fun commasAloneAreNoList() {
        assertTrue(CallText.blocks("Both started: the job is running, and a bug job is on why.").all { it is Para })
        assertTrue(CallText.blocks("Red, green, and blue is how it starts.").all { it is Para })
        assertTrue(CallText.blocks("First question: when you're walking, do you want to hear it, or just glance?").all { it is Para })
        assertTrue(CallText.blocks("Audit notes: when you ask for a build, I start the job, and you never see code.").all { it is Para })
    }

    @Test
    fun anInlineNumberedListKeepsItsNumbers() {
        val blocks = CallText.blocks("Five picks: 1. The tick. 2. The switch. 3. The close. Answer them.")
        assertEquals(listOf("1.", "2.", "3."), blocks.filterIsInstance<Item>().map { it.mark })
        assertEquals("The switch.", blocks[2].spans.single().text)
        assertEquals("Answer them.", (blocks.last() as Para).spans.single().text)
    }

    @Test
    fun dotsInsideASentenceDoNotEndIt() {
        assertEquals(1, CallText.sentences("Narrower 2.4 gigahertz width at 8 p.m. tonight.").size)
        assertEquals(2, CallText.sentences("It needs 200. Then it means something.").size)
        assertEquals(2, CallText.sentences("Like \"from your terminal.\" You always know.").size)
    }

    @Test
    fun markdownIsDrawnNotShown() {
        val blocks = CallText.blocks("**Done.** It's *live* on `master`.\n- one\n- two\n3. three")
        assertEquals("Done. It's live on master. one two 3. three", CallText.plain(blocks))
        assertEquals(listOf(Span("Done.", bold = true), Span(" It's "), Span("live", italic = true), Span(" on "), Span("master"), Span(".")), blocks[0].spans)
        assertEquals(listOf("•", "•", "3."), blocks.filterIsInstance<Item>().map { it.mark })
    }

    @Test
    fun aviv_sLinesGetNoWeightAndNoLists() {
        val l = "First, fix it. Second, ship it. Three things: red, green, and blue. Also tell me."
        val blocks = CallText.blocks(l, mine = true)
        assertTrue(blocks.all { it is Para })
        assertFalse(blocks.flatMap { it.spans }.any { it.bold })
    }

    @Test
    fun aStrayAsteriskStays() = assertEquals("5 * 3 is 15.", CallText.plain(CallText.blocks("5 * 3 is 15.")))
}
