package com.kivan.jarvis.tabs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProseTest {
    @Test
    fun aParagraphBulletAndCodeAreOneBlockEach() {
        val b = Prose.parse("First line\nstill first.\n\n- one\n- two\n\n```\nlet a = 1;\n\nlet b = 2;\n```\nLast.")
        assertEquals(5, b.size)
        assertEquals("First line still first.", b[0].plain)
        assertTrue(b[1] is Block.Item)
        assertEquals("let a = 1;\n\nlet b = 2;", (b[3] as Block.Code).plain)
        assertEquals("Last.", b[4].plain)
    }

    @Test
    fun aQuoteCarriesTheWordsAsTheyStand() {
        val b = Prose.parse("Keep **this** word.")
        assertEquals("Keep **this** word.", b[0].plain)
        assertEquals(listOf(Kind.Plain, Kind.Bold, Kind.Plain), (b[0] as Block.Para).spans.map { it.kind })
    }

    @Test
    fun whatAScreenShowsHasNoMarks() {
        assertEquals("Keep this word.", Prose.parse("Keep **this** word.")[0].shown)
    }

    @Test
    fun numbersKeepTheirMarkAndIndentGivesDepth() {
        val b = Prose.parse("3. third\n  - under")
        assertEquals("3.", (b[0] as Block.Item).mark)
        assertEquals(1, (b[1] as Block.Item).depth)
    }

    @Test
    fun aLinkKeepsItsAddressAndABareOneIsALinkToo() {
        val s = Prose.spans("see [the page](https://example.com/a) or https://example.com/b")
        val links = s.filter { it.kind == Kind.Link }
        assertEquals(listOf("the page", "https://example.com/b"), links.map { it.text })
        assertEquals("https://example.com/a", links[0].url)
    }

    @Test
    fun anOpenCodeFenceStillShowsWhatArrived() {
        val b = Prose.parse("Here:\n```\nhalf a blo")
        assertEquals("half a blo", (b[1] as Block.Code).plain)
    }
}
