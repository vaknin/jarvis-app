package com.kivan.tether.dibs

import androidx.activity.ComponentActivity
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.kivan.tether.dibs.ui.DibsApp
import com.kivan.tether.dibs.ui.theme.AppTheme
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.abs

/**
 * The chat lists never scroll on their own: pinned to the bottom they keep the newest row's bottom
 * at the list's bottom through every change; scrolled up (by a finger) nothing moves the view.
 * On a small screen with two very tall messages, so two rows fill it. The main chat, the Ask page
 * and a task's chat page. Run: `./gradlew :dibs:testDebugUnitTest -Pscreenshots --tests '*ScrollTest*'`.
 * Every check of a test is recorded and reported together, with the measured pixels.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w320dp-h568dp-280dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ScrollTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private lateinit var host: FakeHost
    private lateinit var v: JSONObject
    private val bad = mutableListOf<String>()

    @Before
    fun reset() {
        Taps.reset()
        Dibs.pages.clear()
        Dibs.answered.clear()
        Dibs.fields.clear()
        Dibs.open.clear()
        Dibs.chatSeen.clear()
        Dibs.chat.draft = ""
        Dibs.chat.about = null
        Dibs.chat.replyTo = null
        Dibs.holdTap = null
        Dibs.resetAsks()
        Dibs.resetChat()
    }

    @After
    fun putTheSingletonsBack() {
        Dibs.chat.draft = ""
        Dibs.chatList.requestScrollToItem(0)
    }

    // ---- the harness -------------------------------------------------------------------------

    private fun show(view: JSONObject) {
        v = view
        host = FakeHost(view)
        Dibs.host = host
        WindowCompat.setDecorFitsSystemWindows(compose.activity.window, false)
        compose.setContent { AppTheme { DibsApp() } }
        insets(keyboard = 0)
    }

    private fun insets(keyboard: Int) {
        val bars = Insets.of(0, 63, 0, 63)
        val insets = WindowInsetsCompat.Builder()
            .setInsets(WindowInsetsCompat.Type.systemBars(), bars)
            .setInsets(WindowInsetsCompat.Type.ime(), Insets.of(0, 0, 0, keyboard))
            .setVisible(WindowInsetsCompat.Type.ime(), keyboard > 0)
            .build()
        compose.runOnUiThread { ViewCompat.dispatchApplyWindowInsets(compose.activity.window.decorView, insets) }
        compose.waitForIdle()
    }

    /** The view changed: [change] edits the JSON, the host sends it, the screen settles. */
    private fun push(change: JSONObject.() -> Unit) {
        v.getJSONObject("dibs").change()
        host.view.value = JSONObject(v.toString())
        compose.waitForIdle()
    }

    private fun checks() {
        assertTrue("${bad.size} check(s) failed:\n" + bad.joinToString("\n"), bad.isEmpty())
    }

    /** The list on screen: the biggest scroll container. */
    private fun area(): Rect = compose.onAllNodes(hasScrollAction()).fetchSemanticsNodes().maxByOrNull { it.boundsInRoot.height }!!.boundsInRoot

    /** Every text on screen over the list (once only), by its words, with where it is. */
    private fun shown(): Map<String, Rect> {
        val a = area()
        val out = HashMap<String, Rect>()
        val dup = HashSet<String>()
        val texts = compose.onAllNodes(SemanticsMatcher("text") { it.config.contains(SemanticsProperties.Text) }, useUnmergedTree = true)
        for (n in texts.fetchSemanticsNodes()) {
            val b = n.boundsInRoot
            if (b.height <= 0f || b.bottom <= a.top || b.top >= a.bottom) continue
            val t = n.config[SemanticsProperties.Text].joinToString("|") { it.text }
            if (t.isBlank() || t.endsWith(" new") || t == "Latest") continue
            if (out.put(t, b) != null) dup += t
        }
        dup.forEach { out.remove(it) }
        return out
    }

    private val dp get() = compose.activity.resources.displayMetrics.density

    /** A finger scrolls the list up (the content moves down) through the middle of its tall rows. */
    private fun scrollUp() {
        val all = compose.onAllNodes(hasScrollAction())
        val nodes = all.fetchSemanticsNodes()
        val at = nodes.indices.maxByOrNull { nodes[it].boundsInRoot.height }!!
        all[at].performTouchInput { swipeDown(startY = height * 0.2f, endY = height * 0.8f) }
        compose.waitForIdle()
    }

    /** What is on screen now; after a step, [steady] says whether anything moved. */
    private var before = emptyMap<String, Rect>()

    private fun mark() {
        before = shown()
    }

    /** Nothing on screen that was there moved (within 1 px); the new baseline is now. */
    private fun steady(step: String) {
        val after = shown()
        val common = before.keys.intersect(after.keys)
        if (common.isEmpty()) {
            bad += "$step: nothing on screen in common (before ${before.keys.map { it.take(14) }}, after ${after.keys.map { it.take(14) }})"
        } else {
            for (k in common) {
                val d = after.getValue(k).top - before.getValue(k).top
                if (abs(d) > 1f) bad += "$step: '${k.take(24)}' moved ${d} px"
            }
        }
        before = after
    }

    private fun pillSays(step: String, words: String) {
        val nodes = compose.onAllNodes(SemanticsMatcher("pill") { it.config.getOrNull(SemanticsProperties.TestTag) == "new-pill" }).fetchSemanticsNodes()
        if (nodes.isEmpty()) {
            bad += "$step: no pill (tag new-pill), wanted '$words'"
            return
        }
        runCatching { compose.onNodeWithTag("new-pill").assertTextContains(words) }.onFailure { bad += "$step: pill does not say '$words': ${it.message?.lines()?.first()}" }
    }

    /** The main chat is at the bottom: the first row is the first visible one, flush with the list's bottom. */
    private fun chatPinned(step: String, newest: String? = null) {
        compose.waitForIdle()
        val s = Dibs.chatList
        if (s.firstVisibleItemIndex != 0 || s.firstVisibleItemScrollOffset != 0) {
            bad += "$step: not at the bottom: index ${s.firstVisibleItemIndex} offset ${s.firstVisibleItemScrollOffset} px"
        }
        if (newest != null) newestInSight(step, newest)
    }

    /** [newest] is wholly (or, if taller than the list, its end is) on screen, within a row's padding of the list's bottom. */
    private fun newestInSight(step: String, newest: String, slackDp: Float = 40f) {
        val r = shown().entries.firstOrNull { it.key.startsWith(newest) }?.value
        if (r == null) {
            bad += "$step: '${newest.take(24)}' is not on screen"
            return
        }
        val gap = area().bottom - r.bottom
        if (gap < -1f || gap > slackDp * dp) bad += "$step: '${newest.take(24)}' ends ${gap} px from the list's bottom (allowed 0 to ${slackDp * dp})"
    }

    // ---- data --------------------------------------------------------------------------------

    private fun para(i: Int) =
        "Paragraph $i of a long answer from dibs. It goes on about the home server, the memory it needs, the idle power draw and what moves off the laptop first, so that it wraps onto many lines of the phone's narrow screen."

    private fun tall(tag: String) = (1..10).joinToString("\n\n") { "$tag/$it " + para(it) }

    private fun line(n: Int, mine: Boolean, text: String) = JSONObject()
        .put("id", if (mine) "u-$n" else "s$n").put("n", n).put("who", if (mine) "user" else "dibs").put("text", text)
        .put("ts", NOW - 3600 + n * 10)

    /** Twelve short lines, then two very tall ones (expanded), then a short newest. */
    private fun chatTalk(): List<JSONObject> = (0 until 12).map { i ->
        line(i, i % 3 == 0, if (i % 3 == 0) "Line $i from me" else "dibs's line $i, a little longer so it wraps onto a second line on the phone.")
    } + line(40, false, tall("T40")) + line(41, false, tall("T41")) + line(42, false, NEWEST)

    private fun chatView(talk: List<JSONObject> = chatTalk()): JSONObject {
        Dibs.open["s40"] = true
        Dibs.open["s41"] = true
        return JSONObject().put(
            "dibs",
            JSONObject()
                .put("v", 1).put("now", NOW)
                .put("state", JSONObject().put("brain", "running").put("busy", false))
                .put("talk", JSONArray(talk))
                .put("questions", JSONArray())
                .put("yours", JSONArray())
                .put("badges", JSONObject().put("waiting", 0)),
        )
    }

    private fun state(on: Boolean, words: String? = null): JSONObject.() -> Unit = {
        getJSONObject("state").put("busy", on).put("line", words ?: JSONObject.NULL)
    }

    private fun addLine(n: Int, mine: Boolean, text: String): JSONObject.() -> Unit = { getJSONArray("talk").put(line(n, mine, text)) }

    private fun holdChip(kind: String, note: String?, button: String, action: String, style: String) =
        JSONObject().put("kind", kind).put("note", note ?: JSONObject.NULL).put("button", button).put("action", action).put("style", style)

    private fun type(words: String) {
        compose.onNode(hasSetTextAction()).performTextInput(words)
        compose.waitForIdle()
    }

    private fun send() {
        compose.onNodeWithContentDescription("Send").performClick()
        compose.waitForIdle()
    }

    // ---- (a) the main chat, pinned at the bottom ---------------------------------------------

    @Test
    fun pinnedTheNewestStaysInSightThroughViewChanges() {
        show(chatView())
        chatPinned("start", NEWEST)
        push(state(true, "Reading the board"))
        chatPinned("busy on", NEWEST)
        push(state(true, "Reading the board and the three tasks it links to, then checking the usage windows one after another, slowly"))
        chatPinned("status longer", NEWEST)
        push(state(false))
        chatPinned("busy off", NEWEST)
        push(addLine(43, false, "A new line from dibs."))
        chatPinned("new dibs line", "A new line from dibs.")
        Dibs.open["s44"] = true
        push(addLine(44, false, tall("T44")))
        chatPinned("new tall dibs line")
        push { getJSONObject("state").put("busy", true).put("hold", holdChip("wait", null, "Wait", "hold", "outline")) }
        chatPinned("hold chip on")
        push { getJSONObject("state").put("busy", false).remove("hold") }
        chatPinned("hold chip off")
        checks()
    }

    @Test
    fun pinnedMySendingAndItsLineKeepTheNewestInSight() {
        show(chatView())
        type("Okay, do it")
        send()
        chatPinned("echo", "Okay, do it")
        val uid = host.sentUids.last()
        // dibs lists the line under the uid, and the echo is swapped for it.
        push { getJSONArray("talk").put(JSONObject().put("id", uid).put("n", 43).put("who", "user").put("text", "Okay, do it").put("ts", NOW - 5).put("uid", uid)) }
        chatPinned("echo swapped for its line", "Okay, do it")
        push(state(true, "On it"))
        chatPinned("busy on after my line", "Okay, do it")
        checks()
    }

    @Test
    fun pinnedTheBoxGrowingKeepsTheNewestInSight() {
        show(chatView())
        chatPinned("start", NEWEST)
        val h0 = compose.onNode(hasSetTextAction()).fetchSemanticsNode().size.height
        type("one two three four five six seven eight nine ten eleven twelve thirteen fourteen fifteen sixteen seventeen eighteen nineteen twenty")
        val h1 = compose.onNode(hasSetTextAction()).fetchSemanticsNode().size.height
        assertTrue("the box did not grow ($h0 -> $h1 px)", h1 > h0 * 2)
        chatPinned("box of 3 lines", NEWEST)
        checks()
    }

    @Test
    fun pinnedTheKeyboardOpeningKeepsTheNewestInSight() {
        show(chatView())
        chatPinned("start", NEWEST)
        insets(keyboard = 450)
        chatPinned("keyboard open", NEWEST)
        push(state(true, "Reading the board"))
        chatPinned("busy on with the keyboard open", NEWEST)
        insets(keyboard = 0)
        chatPinned("keyboard closed", NEWEST)
        checks()
    }

    @Test
    fun pinnedALongMessageExpandingKeepsItsEndInSight() {
        val long = (1..8).joinToString(" ") { para(it) }
        show(chatView(chatTalk().dropLast(1) + line(42, false, long)))
        chatPinned("start")
        compose.onAllNodesWithText("More")[0].performClick()
        compose.waitForIdle()
        chatPinned("More tapped")
        newestInSight("More tapped", "Paragraph 1 of a long answer", slackDp = 400f)
        checks()
    }

    // ---- (b) scrolled up by a finger: nothing moves the view ---------------------------------

    @Test
    fun scrolledUpBusyAndNewLinesDoNotMoveTheView() {
        show(chatView())
        scrollUp()
        val s = Dibs.chatList
        assertTrue("the swipe did not scroll into the tall rows: index ${s.firstVisibleItemIndex}", s.firstVisibleItemIndex in 1..2)
        mark()
        assertTrue("nothing on screen: ${before.keys}", before.isNotEmpty())
        push(state(true, "Reading the board"))
        steady("busy on")
        push(state(true, "Reading the board and the three tasks it links to, then checking the usage windows one after another, slowly"))
        steady("status longer")
        push(state(false))
        steady("busy off")
        push(addLine(43, false, "A new line from dibs."))
        steady("new dibs line")
        pillSays("new dibs line", "1 new")
        push(state(true, "Thinking"))
        steady("busy on after the new line")
        pillSays("busy on after the new line", "1 new")
        push { getJSONObject("state").put("hold", holdChip("wait", null, "Wait", "hold", "outline")) }
        steady("hold chip on")
        checks()
    }

    // ---- (c) scrolled up, then I send ---------------------------------------------------------

    @Test
    fun scrolledUpSendingDoesNotMoveTheView() {
        show(chatView())
        scrollUp()
        mark()
        assertTrue("nothing on screen: ${before.keys}", before.isNotEmpty())
        type("Okay, do it")
        steady("typing")
        send()
        steady("echo")
        pillSays("echo", "1 new")
        val uid = host.sentUids.last()
        push { getJSONArray("talk").put(JSONObject().put("id", uid).put("n", 43).put("who", "user").put("text", "Okay, do it").put("ts", NOW - 5).put("uid", uid)) }
        steady("echo swapped for its line")
        pillSays("echo swapped for its line", "1 new")
        checks()
    }

    // ---- (d) the place survives a restart (main chat) ----------------------------------------

    @Test
    fun scrollingUpKeepsThePlaceAndPinnedKeepsPinned() {
        show(chatView())
        compose.mainClock.advanceTimeBy(1000)
        assertTrue("pinned not kept: ${host.notes}", host.notes["chat-place"] == "pinned")
        scrollUp()
        compose.mainClock.advanceTimeBy(1000)
        val kept = host.notes["chat-place"]
        assertTrue("place not kept: $kept", kept != null && kept.startsWith("at\t"))
    }

    @Test
    fun aKeptPlaceIsGoneBackToAfterARestart() {
        Dibs.resetChat()
        val vw = chatView()
        val host0 = FakeHost(vw)
        host0.notes["chat-place"] = "at\t0\ts41"
        Dibs.host = host0
        WindowCompat.setDecorFitsSystemWindows(compose.activity.window, false)
        v = vw
        host = host0
        compose.setContent { AppTheme { DibsApp() } }
        insets(keyboard = 0)
        compose.mainClock.advanceTimeBy(1000)
        val first = Dibs.chatList.layoutInfo.visibleItemsInfo.first().key
        assertTrue("restored to s41, first visible is $first", first == "s41")
        assertTrue("still pinned", !Dibs.chatPin.pinned)
        push(addLine(43, false, "A new line from dibs."))
        val after = Dibs.chatList.layoutInfo.visibleItemsInfo.first().key
        assertTrue("moved to $after", after == "s41")
    }

    @Test
    fun aKeptPinnedOrAGoneRowOpensAtTheBottom() {
        val vw = chatView()
        val host0 = FakeHost(vw)
        host0.notes["chat-place"] = "at\t0\tno-such-row"
        Dibs.host = host0
        v = vw
        host = host0
        compose.setContent { AppTheme { DibsApp() } }
        insets(keyboard = 0)
        chatPinned("gone row", NEWEST)
        checks()
    }

    // ---- (e) the Ask page ---------------------------------------------------------------------

    private fun threads(): JSONArray = JSONObject(javaClass.getResource("/threads.json")!!.readText()).getJSONArray("threads")

    private fun askLine(i: Int, who: String, text: String) =
        JSONObject().put("id", "t1-$i").put("who", who).put("text", text).put("ts", NOW - 3000 + i * 10).apply { if (who == "user") put("uid", "p-$i") }

    private val askAbout = "note:aaaaaaaaaaaaaaaa0000000000000041"

    private fun askView(): JSONObject {
        val lines = JSONArray()
            .put(askLine(1, "user", "Why 32 GB and not 16?"))
            .put(askLine(2, "dibs", "Two Rust builds at once peak at about 13 GB."))
            .put(askLine(3, "user", "And is it quiet?"))
            .put(askLine(4, "dibs", tall("A4")))
            .put(askLine(5, "dibs", tall("A5")))
            .put(askLine(6, "dibs", "Short last answer."))
        val t = threads().getJSONObject(0).put("state", "open").put("lines", lines).put("asked", JSONArray()).put("status", JSONObject.NULL)
            .put("overview", JSONObject.NULL).put("reading", JSONObject.NULL)
        return JSONObject().put(
            "dibs",
            JSONObject().put("v", 1).put("now", NOW)
                .put("state", JSONObject().put("brain", "running").put("busy", false))
                .put("talk", JSONArray()).put("questions", JSONArray()).put("yours", JSONArray()).put("threads", JSONArray().put(t)),
        )
    }

    private fun thread(v: JSONObject) = v.getJSONObject("dibs").getJSONArray("threads").getJSONObject(0)

    private fun askStatus(on: Boolean, words: String = "Answering 1 question · reading the note"): JSONObject.() -> Unit = {
        getJSONArray("threads").getJSONObject(0).put("status", if (on) JSONObject().put("busy", true).put("words", words) else JSONObject.NULL)
    }

    private fun askLineAdded(i: Int, who: String, text: String): JSONObject.() -> Unit = {
        getJSONArray("threads").getJSONObject(0).getJSONArray("lines").put(askLine(i, who, text))
    }

    private fun openAsk() {
        show(askView())
        compose.runOnUiThread { Dibs.open(Page.Ask(askAbout)) }
        compose.waitForIdle()
    }

    @Test
    fun askPinnedTheNewestStaysInSight() {
        openAsk()
        newestInSight("start", "Short last answer.")
        push(askStatus(true))
        newestInSight("status on", "Answering 1 question")
        push(askStatus(true, "Answering 2 questions · reading the note and the three tasks it links to, then the usage windows, slowly"))
        newestInSight("status longer", "Answering 2 questions")
        push(askStatus(false))
        newestInSight("status off", "Short last answer.")
        push(askLineAdded(7, "dibs", "A new answer."))
        newestInSight("new answer", "A new answer.")
        type("And the price?")
        send()
        newestInSight("my line", "And the price?")
        checks()
    }

    @Test
    fun askScrolledUpNothingMovesTheView() {
        openAsk()
        scrollUp()
        mark()
        assertTrue("nothing on screen: ${before.keys}", before.isNotEmpty())
        assertTrue("the swipe did not leave the newest: ${before.keys.map { it.take(12) }}", before.keys.none { it.startsWith("Short last answer") })
        push(askStatus(true))
        steady("status on")
        push(askStatus(true, "Answering 2 questions · reading the note and the three tasks it links to, then the usage windows, slowly"))
        steady("status longer")
        push(askStatus(false))
        steady("status off")
        push(askLineAdded(7, "dibs", "A new answer."))
        steady("new answer")
        type("And the price?")
        send()
        steady("my line")
        checks()
    }

    // ---- (e) a task's chat page ---------------------------------------------------------------

    private fun taskLine(i: Int, who: String, text: String) =
        JSONObject().put("id", "k$i").put("who", who).put("text", text).put("ts", NOW - 3000 + i * 10)

    private fun task(): JSONObject = JSONObject()
        .put("id", 31).put("title", "Cleanup").put("name", "t31").put("project", "tether").put("state", "working")
        .put("ts", NOW - 60).put("started", NOW - 3600).put("asked", "Cleanup").put("unread", false).put("line", "What it did last")
        .put("busy", false)
        .put("talk", JSONArray()
            .put(taskLine(1, "user", "Please also check the notes."))
            .put(taskLine(2, "agent", "Checked, nothing there."))
            .put(taskLine(3, "user", "And the logs?"))
            .put(taskLine(4, "agent", tall("K4")))
            .put(taskLine(5, "agent", tall("K5"))))

    private fun taskView(): JSONObject = JSONObject().put(
        "dibs",
        JSONObject().put("v", 1).put("now", NOW)
            .put("state", JSONObject().put("brain", "running").put("busy", false))
            .put("talk", JSONArray()).put("questions", JSONArray()).put("yours", JSONArray().put(task())),
    )

    private fun inTask(change: JSONObject.() -> Unit): JSONObject.() -> Unit = { getJSONArray("yours").getJSONObject(0).change() }

    private fun taskBusy(on: Boolean): JSONObject.() -> Unit = inTask { put("busy", on) }

    private fun taskLineAdded(i: Int, who: String, text: String): JSONObject.() -> Unit = inTask { getJSONArray("talk").put(taskLine(i, who, text)) }

    private fun openTask() {
        show(taskView())
        compose.runOnUiThread { Dibs.open(Page.Task(31)) }
        compose.waitForIdle()
    }

    @Test
    fun taskOpensAtTheVeryEndOfTheAgentsTallReplyAndFollowsWhatGrowsThere() {
        openTask()
        // The agent replied since the page was last open: it opens at the end of the chat.
        newestInSight("open", "K5/1 ")
        push(taskBusy(true))
        newestInSight("busy on", "Cleanup is on it")
        push(taskBusy(false))
        newestInSight("busy off", "K5/1 ")
        push(taskLineAdded(6, "agent", "A short new line."))
        newestInSight("new line", "A short new line.")
        push(taskLineAdded(7, "agent", tall("K7")))
        newestInSight("new tall line", "K7/1 ")
        checks()
    }

    @Test
    fun taskScrolledUpNothingMovesTheView() {
        openTask()
        scrollUp()
        mark()
        assertTrue("nothing on screen: ${before.keys}", before.isNotEmpty())
        push(taskBusy(true))
        steady("busy on")
        push(taskBusy(false))
        steady("busy off")
        push(taskLineAdded(6, "agent", "A short new line."))
        steady("new line")
        push(taskLineAdded(7, "agent", tall("K7")))
        steady("new tall line")
        val uid = "mine-1"
        compose.runOnUiThread { Dibs.addPending(Pending(uid, "Okay, do it", emptyList(), task = 31)) }
        compose.waitForIdle()
        steady("my line")
        checks()
    }

    private companion object {
        val NOW = System.currentTimeMillis() / 1000
        const val NEWEST = "dibs's newest line, short."
    }
}
