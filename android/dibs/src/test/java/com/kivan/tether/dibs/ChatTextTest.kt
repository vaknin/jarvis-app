package com.kivan.tether.dibs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatTextTest {
    private val f = "```"

    @Test
    fun noFenceIsOnePlainPartUnchanged() {
        assertEquals(listOf(Plain("see https://x.io/a_b  now\n\nok")), textParts("see https://x.io/a_b  now\n\nok"))
        assertEquals(listOf(Plain("")), textParts(""))
    }

    @Test
    fun aClosedBlockWithTextAround() {
        assertEquals(
            listOf(Plain("Run this:"), Code("free -h"), Plain("then tell me")),
            textParts("Run this:\n$f\nfree -h\n$f\nthen tell me"),
        )
    }

    @Test
    fun theLanguageWordIsDropped() {
        assertEquals(listOf(Code("val a = 1")), textParts("${f}kotlin\nval a = 1\n$f"))
        assertEquals(listOf(Code("x")), textParts("$f  whatever else\nx\n$f trailing"))
    }

    @Test
    fun aFenceMayHaveLeadingSpaces() {
        assertEquals(listOf(Plain("a"), Code("x"), Plain("b")), textParts("a\n   $f\nx\n  $f\nb"))
    }

    @Test
    fun aFenceNeverClosedRunsToTheEnd() {
        assertEquals(listOf(Plain("a"), Code("x\ny")), textParts("a\n$f\nx\ny"))
    }

    @Test
    fun severalBlocks() {
        assertEquals(
            listOf(Code("one"), Plain("mid"), Code("two")),
            textParts("$f\none\n$f\nmid\n$f\ntwo\n$f"),
        )
    }

    @Test
    fun aFenceInTheMiddleOfALineIsPlain() {
        val t = "use ${f}x$f here\nand more"
        assertEquals(listOf(Plain(t)), textParts(t))
    }

    @Test
    fun crlfIsReadAsLf() {
        assertEquals(listOf(Plain("a"), Code("x\ny"), Plain("b")), textParts("a\r\n$f\r\nx\r\ny\r\n$f\r\nb"))
    }

    @Test
    fun codeKeepsIndentationAndInnerBlankLines() {
        assertEquals(listOf(Code("fn a() {\n\n    b()\n}")), textParts("$f\nfn a() {\n\n    b()\n}\n$f"))
    }

    @Test
    fun anEmptyBlockIsDropped() {
        assertEquals(listOf(Plain("a"), Plain("b")), textParts("a\n$f\n\n  \n$f\nb"))
        assertEquals(emptyList<TextPart>(), textParts("$f\n$f"))
        assertEquals(emptyList<TextPart>(), textParts(f))
    }

    @Test
    fun blankLinesNextToAFenceAreTrimmed() {
        assertEquals(
            listOf(Plain("a"), Code("x"), Plain("b")),
            textParts("a\n\n$f\nx\n$f\n\nb"),
        )
    }

    @Test
    fun hebrewTextBeforeABlockIsKept() {
        assertEquals(
            listOf(Plain("הרץ את זה:"), Code("ls -l")),
            textParts("הרץ את זה:\n$f\nls -l\n$f"),
        )
    }

    @Test
    fun shortPartsStayWhole() {
        val parts = listOf(Plain("hi"), Code("a\nb"))
        assertEquals(Folded(parts, false), foldParts(parts))
    }

    @Test
    fun longCodeIsCutByLines() {
        val code = (1..30).joinToString("\n") { "l$it" }
        val fold = foldParts(listOf(Code(code)))
        assertTrue(fold.cut)
        assertEquals(listOf(Code((1..FOLD_ROWS).joinToString("\n") { "l$it" })), fold.parts)
    }

    @Test
    fun longPlainIsCutByLinesThatFit() {
        val text = (1..30).joinToString("\n") { "line $it" }
        val fold = foldParts(listOf(Plain(text)), 5)
        assertTrue(fold.cut)
        assertEquals(listOf(Plain("line 1\nline 2\nline 3\nline 4\nline 5")), fold.parts)
    }

    @Test
    fun aSingleLongLineIsCutByCharacters() {
        val fold = foldParts(listOf(Plain("x".repeat(500))), 3)
        assertTrue(fold.cut)
        assertEquals(listOf(Plain("x".repeat(3 * ROW_CHARS))), fold.parts)
    }

    @Test
    fun theBudgetCanRunOutBetweenParts() {
        val a = Plain((1..4).joinToString("\n") { "a$it" })
        val b = Code("b1\nb2\nb3\nb4\nb5\nb6")
        val fold = foldParts(listOf(a, b), 6)
        assertTrue(fold.cut)
        assertEquals(listOf(a, Code("b1\nb2")), fold.parts)
        // Exactly full: the next part is left out whole.
        val exact = foldParts(listOf(a, b), 4)
        assertEquals(Folded(listOf(a), true), exact)
    }

    @Test
    fun cutIsFalseWhenEverythingFits() {
        assertFalse(foldParts(listOf(Code((1..FOLD_ROWS).joinToString("\n") { "l$it" }))).cut)
    }
}
