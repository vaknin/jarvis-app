package com.kivan.jarvis.tabs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** What leaves the reply page: every send says what it answers. */
class ReplyPiecesTest {
    private val one = Prose.parse("The job is queued.")
    private val two = Prose.parse("First paragraph.\n\nSecond paragraph.")

    @Test
    fun wordsInTheEndBoxQuoteTheAnswer() {
        val (quotes, end) = replyPieces(7, one, ReplyDraft(7, end = " same "))
        assertEquals(listOf(Quoted(7, "The job is queued.", "same")), quotes)
        assertEquals("", end)
    }

    @Test
    fun aParagraphAnsweredQuotesThatParagraph() {
        val (quotes, end) = replyPieces(7, two, ReplyDraft(7, mapOf(1 to Note("", "yes"))))
        assertEquals(listOf(Quoted(7, "Second paragraph.", "yes")), quotes)
        assertEquals("", end)
    }

    @Test
    fun anythingElseFollowsTheQuotesItWasWrittenWith() {
        val (quotes, end) = replyPieces(7, two, ReplyDraft(7, mapOf(0 to Note("", "yes")), "and more"))
        assertEquals(listOf(Quoted(7, "First paragraph.", "yes")), quotes)
        assertEquals("and more", end)
    }

    @Test
    fun wordsHePickedGoEvenWithNothingUnderThem() {
        val (quotes, end) = replyPieces(7, two, ReplyDraft(7, mapOf(1 to Note("Second", "")), "same"))
        assertEquals(listOf(Quoted(7, "Second", "")), quotes)
        assertEquals("same", end)
    }

    @Test
    fun aParagraphLeftEmptyIsSkipped() {
        val (quotes, _) = replyPieces(7, two, ReplyDraft(7, mapOf(0 to Note("", " ")), "same"))
        assertEquals(1, quotes.size)
        assertEquals("First paragraph. Second paragraph.", quotes[0].quote)
    }

    @Test
    fun aLongAnswerSendsItsStart() {
        val long = Prose.parse("word ".repeat(400))
        val (quotes, _) = replyPieces(7, long, ReplyDraft(7, end = "same"))
        assertTrue(quotes[0].quote.length <= 281)
        assertTrue(quotes[0].quote.endsWith("…"))
    }

    @Test
    fun anAnswerWithNoWordsHasNothingToQuote() {
        assertEquals(emptyList<Quoted>() to "same", replyPieces(7, emptyList(), ReplyDraft(7, end = "same")))
    }
}
