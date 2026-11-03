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
    private val rec = TileRec("t1", 3u, "Piano app", needs = false, unread = true, working = false, mode = "", model = "opus", effort = "medium", autopilot = false)

    private fun tab(from: UInt, vararg entries: EntryRec) =
        TabRec(rec, from, entries.toList(), working = false, what = "", since = 0u, jobs = "", waiting = false, closed = false, loading = false, asks = emptyList())

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
        val saved = Saved(listOf(tile), mapOf("" to "wall words", "t1" to "tab words"), mapOf(draftKey("t1", 41) to draft, draftKey("t1", 57) to ReplyDraft(57, end = "another")), listOf(Attached("/cache/a/plot.png", "plot.png", true)))
        val back = Saved.parse(saved.json())!!
        assertEquals(listOf(tile.copy(working = false)), back.tiles)
        assertEquals(saved.typed, back.typed)
        assertEquals(saved.drafts, back.drafts)
        assertEquals(saved.attached, back.attached)
    }

    @Test
    fun aDraftSavedPerTabIsKeptUnderItsAnswer() {
        val old = """{"tiles":[],"typed":{},"drafts":{"t1":{"to":41,"end":"mine","notes":{}}}}"""
        assertEquals(mapOf(draftKey("t1", 41) to ReplyDraft(41, end = "mine")), Saved.parse(old)!!.drafts)
    }

    @Test
    fun aValueFromBeforeTheTrayStillReads() {
        val old = """{"tiles":[],"typed":{"t1":"tab words"},"drafts":{},"muted":true}"""
        val back = Saved.parse(old)!!
        assertEquals("tab words", back.typed["t1"])
        assertEquals(emptyList<Attached>(), back.attached)
    }

    @Test
    fun anOlderBuildCanStillReadWhatThisOneSaves() {
        // Build 281 refuses a value with no "muted": the way back must not cost him his words.
        assertEquals(false, org.json.JSONObject(Saved(emptyList(), emptyMap(), emptyMap(), emptyList()).json()).getBoolean("muted"))
    }

    @Test
    fun sendTakesWhatHeTypedAndWhatHeSaidAsOneMessage() {
        assertEquals("typed said", said(" typed ", "said"))
        assertEquals("said", said("", "said"))
        assertEquals("typed", said("typed", " "))
        assertEquals("", said(" ", ""))
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

    private val held = QuoteMark(41, 0, "first", "go at the end", quiet = true)
    private val editing = QuoteMark(41, 1, "second", "half an edit")
    private val marked = QuoteMark(57, 0, "third")

    private fun at(working: Boolean, marks: List<QuoteMark> = listOf(held, editing, marked)) = DeckState(
        tiles = listOf(TileView("t1", 1, "Piano app", working = working)),
        tabs = mapOf("t1" to TabView(TileView("t1", 1, "Piano app"), work = if (working) WorkView("thinking", 1) else null)),
        marks = mapOf("t1" to marks),
    )

    @Test
    fun leavingTheAppTakesEveryQuietReplyAndLeavesTheRest() {
        val (after, going) = at(false).quietAll()
        assertEquals(mapOf("t1" to listOf(Quoted(41, "first", "go at the end"))), going)
        assertEquals(listOf(editing, marked), after.marks["t1"])
        val (empty, gone) = at(false, listOf(held)).quietAll()
        assertNull(empty.marks["t1"])
        assertEquals(1, gone.getValue("t1").size)
    }

    @Test
    fun aQuietReplySurvivesARestart() {
        val back = Saved.parse(Saved(emptyList(), emptyMap(), emptyMap(), emptyList(), mapOf("t1" to listOf(held, editing))).json())!!
        assertEquals(listOf(held, editing), back.marks["t1"])
    }
}
