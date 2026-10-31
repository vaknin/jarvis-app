package com.kivan.jarvis.tabs

import com.kivan.jarvis.core.EntryRec
import com.kivan.jarvis.core.TabRec
import com.kivan.jarvis.core.TileRec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = android.app.Application::class)
class RealDeckTest {
    private val rec = TileRec("t1", 3u, "Piano app", needs = false, unread = true, working = false, readOnly = false)

    private fun tab(from: UInt, vararg entries: EntryRec) =
        TabRec(rec, from, entries.toList(), working = false, what = "", since = 0u, closed = false, loading = false)

    private fun quiet(key: String) = EntryRec.Quiet(key, key)

    @Test
    fun aTabFrameReplacesFromItsFromOn() {
        val s = DeckState().with(tab(0u, quiet("a"), quiet("b"), quiet("c"))).with(tab(1u, quiet("x")))
        assertEquals(listOf("a", "x"), s.tabs["t1"]!!.entries.map { it.key })
    }

    @Test
    fun anEmptyWallWithNoLinkKeepsTheLastOne() {
        val s = DeckState(tiles = listOf(TileView("t1", 3, "Piano app")))
        assertEquals(1, s.walled(emptyList(), emptyList(), online = false).tiles.size)
        assertEquals(false, s.walled(emptyList(), emptyList(), online = false).online)
        assertEquals(0, s.walled(emptyList(), emptyList(), online = true).tiles.size)
    }

    @Test
    fun aNewWallReachesTheTabsTile() {
        val s = DeckState().with(tab(0u)).walled(listOf(rec.copy(unread = false)), emptyList(), online = true)
        assertEquals(false, s.tabs["t1"]!!.tile.unread)
    }

    @Test
    fun wordsThatDidNotLandComeBackAboveNewOnes() {
        assertEquals("lost", DeckState().typedBack("t1", "lost").typed["t1"])
        assertEquals("lost\n\nnew", DeckState(typed = mapOf("t1" to "new")).typedBack("t1", "lost").typed["t1"])
    }

    @Test
    fun savedWordsSurviveARestart() {
        val tile = TileView("t1", 3, "Piano \"app\"", needs = true, working = true)
        val draft = ReplyDraft(41, mapOf(2 to Note("some words", "mine\nand more")), "the end")
        val saved = Saved(listOf(tile), mapOf("" to "wall words", "t1" to "tab words"), mapOf("t1" to draft), muted = true)
        val back = Saved.parse(saved.json())!!
        assertEquals(listOf(tile.copy(working = false)), back.tiles)
        assertEquals(saved.typed, back.typed)
        assertEquals(saved.drafts, back.drafts)
        assertEquals(true, back.muted)
    }

    @Test
    fun aFileNameCannotClimbOutOfItsFolder() {
        val dir = java.io.File("/cache/tabfiles")
        for (name in listOf("../../shared_prefs/tabs.xml", "..", ".", "/etc/passwd", "a\\..\\b", "")) {
            val at = fileAt(dir, "t1", name)
            assertEquals(name, dir.path, at.parentFile!!.parent)
            assertEquals(name, at.path, at.normalize().path)
        }
        assertEquals("plot.png", fileAt(dir, "t1", "plot.png").name)
    }

    @Test
    fun aBrokenSaveIsIgnored() = assertNull(Saved.parse("{nope"))
}
