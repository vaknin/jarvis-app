package com.kivan.tether.dibs

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** A tap shows at once, and fails plainly when dibs never lists it among the inputs it has read. */
class TapsTest {
    private lateinit var host: FakeHost
    private var t = 1_000_000L
    private val task = YourTask(id = 31, title = "Recap", name = "recap", project = "tether", state = "done", ts = 9, started = 1)

    @Before
    fun reset() {
        host = FakeHost(null)
        Dibs.host = host
        Dibs.resetAsks()
        Dibs.answered.clear()
        Dibs.hidden.clear()
        Dibs.clock = { t }
        Dibs.upSince = t
    }

    /** A view as dibs sends it; [heard] null: a dibs that doesn't list them. */
    private fun view(heard: List<String>?): DibsView {
        val o = JSONObject().put("talk", JSONArray())
        heard?.let { o.put("heard", JSONArray(it)) }
        return DibsView.parse(o)
    }

    private fun stop() = Dibs.stop(31)

    @Test
    fun aTapIsSendingUntilItsUidIsHeard() {
        Dibs.seen(view(emptyList()))
        val uid = Dibs.tap("stop:31", "Stopping task 31", "stop task 31", "stop")
        assertEquals(Dibs.TapState.SENDING, Dibs.tapState(Dibs.taps.single()))
        assertTrue(Dibs.busy("stop:31"))
        assertFalse(Dibs.busy("stop:32"))
        Dibs.seen(view(listOf("other")))
        assertTrue("another input isn't it", Dibs.busy("stop:31"))
        Dibs.seen(view(listOf("other", uid)))
        assertTrue(Dibs.taps.isEmpty())
        assertFalse(Dibs.busy("stop:31"))
    }

    @Test
    fun aHeardTapWithWordsShowsThemForThreeSeconds() {
        Dibs.seen(view(emptyList()))
        val uid = Dibs.tap("reopen:31", "Opening on the laptop", "open it on the laptop", "reopen", done = "Opened on the laptop")
        Dibs.seen(view(listOf(uid)))
        assertEquals(Dibs.TapState.HEARD, Dibs.tapState(Dibs.taps.single()))
        assertFalse("heard: the button is free", Dibs.busy("reopen:31"))
        t += 2_999
        Dibs.expire()
        assertEquals(1, Dibs.taps.size)
        t += 1
        Dibs.expire()
        assertTrue(Dibs.taps.isEmpty())
    }

    @Test
    fun aTapNotHeardAfterTwentySecondsOfLinkFailsAndPutsBackWhatItChanged() {
        Dibs.seen(view(emptyList()))
        Dibs.answered["q5"] = "Approve"
        Dibs.hidden["l9"] = true
        val tap = Dibs.tap("q5", "Sending your answer", "your answer", "a5", restore = "q5", restoreLabel = "Approve", unhide = "l9")
        t += 19_999
        Dibs.expire()
        assertEquals(Dibs.TapState.SENDING, Dibs.tapState(Dibs.taps.single()))
        assertTrue(Dibs.answered.containsKey("q5"))
        t += 1
        assertFalse("nothing is left to wait for", Dibs.expire())
        assertEquals(Dibs.TapState.UNHEARD, Dibs.tapState(Dibs.taps.single()))
        assertFalse("the card comes back", Dibs.answered.containsKey("q5"))
        assertFalse("the line comes back", Dibs.hidden.containsKey("l9"))
        assertFalse("a failed tap is no longer busy", Dibs.busy("q5"))
        // It stays failed, whatever the link does.
        Dibs.upSince = null
        assertEquals(Dibs.TapState.UNHEARD, Dibs.tapState(Dibs.taps.single()))
        Dibs.upSince = t

        Dibs.retry(Dibs.taps.single())
        assertEquals("sent again", 2, host.acts.size)
        assertEquals("a5", host.acts.last().first)
        val again = Dibs.taps.single()
        assertNotEquals("a new uid", tap, again.uid)
        assertEquals(Dibs.TapState.SENDING, Dibs.tapState(again))
        assertEquals("the card is as it was while it goes", "Approve", Dibs.answered["q5"])
        assertTrue(Dibs.hidden.containsKey("l9"))
    }

    @Test
    fun aTapWhileTheLinkIsDownWaitsAndNeverFails() {
        Dibs.seen(view(emptyList()))
        Dibs.upSince = null
        stop()
        t += 3_600_000
        Dibs.expire()
        assertEquals(Dibs.TapState.WAITING, Dibs.tapState(Dibs.taps.single()))
        assertTrue("still a spinner: it will go", Dibs.busy("stop:31"))
        // The link comes up: the 20 s count from then.
        Dibs.upSince = t
        t += 19_999
        Dibs.expire()
        assertEquals(Dibs.TapState.SENDING, Dibs.tapState(Dibs.taps.single()))
        t += 1
        Dibs.expire()
        assertEquals(Dibs.TapState.UNHEARD, Dibs.tapState(Dibs.taps.single()))
    }

    @Test
    fun aDibsThatDoesNotListHeardNeverFailsATap() {
        Dibs.seen(view(null))
        stop()
        t += 14_999
        Dibs.expire()
        assertEquals(Dibs.TapState.SENDING, Dibs.tapState(Dibs.taps.single()))
        t += 1
        Dibs.expire()
        assertTrue("it clears quietly", Dibs.taps.isEmpty())
        // The same from a view arriving.
        stop()
        t += 15_000
        Dibs.seen(view(null))
        assertTrue(Dibs.taps.isEmpty())
    }

    @Test
    fun theSameKeyTwiceIsOneTap() {
        Dibs.seen(view(emptyList()))
        stop()
        stop()
        assertEquals(2, host.acts.size)
        assertEquals(1, Dibs.taps.size)
        assertEquals(host.acts.size, Dibs.taps.single().uid.removePrefix("uid").toInt())
    }

    @Test
    fun aLateHeardRemovesAFailedTap() {
        Dibs.seen(view(emptyList()))
        val uid = Dibs.tap("hide:l1", "Hiding", "hide it", "h1")
        t += 20_000
        Dibs.expire()
        assertEquals(Dibs.TapState.UNHEARD, Dibs.tapState(Dibs.taps.single()))
        Dibs.seen(view(listOf(uid)))
        assertTrue(Dibs.taps.isEmpty())
    }

    @Test
    fun theButtonsGoThroughTheTracker() {
        Dibs.seen(view(emptyList()))
        Dibs.tick(task)
        assertEquals("tick:31", Dibs.taps.single().key)
        assertEquals("ticked", Dibs.answered["tick:31"])
        Dibs.untick(task)
        assertEquals("untick:31", Dibs.taps.single { it.key == "untick:31" }.key)
        Dibs.taskAct("task:31", "up")
        assertEquals("Moving up", Dibs.taps.single { it.key == "act:task:31" }.doing)
        Dibs.answer("q7", "Yes", "a7")
        assertEquals("q7", Dibs.taps.single { it.key == "q7" }.restore)
        // A tick that dibs never heard comes back unticked.
        t += 20_000
        Dibs.expire()
        assertFalse(Dibs.answered.containsKey("untick:31"))
        assertFalse(Dibs.answered.containsKey("q7"))
    }

    @Test
    fun heardIsParsed() {
        assertEquals(setOf("a", "b"), view(listOf("a", "b")).heard)
        assertEquals(emptySet<String>(), view(emptyList()).heard)
        assertEquals(null, view(null).heard)
    }
}
