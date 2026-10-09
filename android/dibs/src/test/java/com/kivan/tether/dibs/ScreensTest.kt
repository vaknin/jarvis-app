package com.kivan.tether.dibs

import android.content.ClipboardManager
import android.net.Uri
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.swipeRight
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.hasText
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import com.github.takahirom.roborazzi.captureRoboImage
import com.kivan.tether.dibs.ui.DibsApp
import com.kivan.tether.dibs.ui.RefCard
import com.kivan.tether.dibs.ui.linkified
import androidx.compose.ui.text.LinkAnnotation
import com.kivan.tether.dibs.ui.theme.AppTheme
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** What the screens asked of the host, and a view to show. */
class FakeHost(view: JSONObject?) : DibsHost {
    override val view = MutableStateFlow(view)
    override val link = MutableStateFlow(Link.CONNECTED)
    override val pickDir = File(System.getProperty("java.io.tmpdir"), "dibs-pick")
    override val uploads: StateFlow<Map<String, Float>> = MutableStateFlow(emptyMap())
    val acts = mutableListOf<Pair<String, JSONObject?>>()
    /** Each act's uid, in step with [acts] (null when none was given). */
    val uids = mutableListOf<String?>()
    /** Files dibs "sent", by the prefix a screen asks for (`story-31-`). */
    val files = mutableMapOf<String, File>()

    override fun act(action: String, value: JSONObject?, uid: String?): String {
        acts += action to value
        uids += uid
        return uid ?: "uid"
    }

    override fun send(uid: String, text: String, files: List<Uri>, action: String, extra: JSONObject?, onFile: (Uri, String) -> Unit) {}
    override fun channelFile(prefix: String): Flow<File?> = flowOf(files[prefix])
    override fun thumb(fileId: String): ImageBitmap? = null
    override val thumbs: StateFlow<Int> = MutableStateFlow(0)
    override fun visible(on: Boolean) {}
    override fun openTether(classic: Boolean) {}
}

/**
 * dibs's screens with a made-up view, on the JVM. PNGs go to dibs/build/outputs/roborazzi/ with
 * `./gradlew :dibs:testDebugUnitTest -Pscreenshots --tests '*ScreensTest*'`; without -Pscreenshots
 * these only check what's on screen.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w412dp-h915dp-420dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ScreensTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private lateinit var host: FakeHost

    @Before
    fun reset() {
        Taps.reset()
        Dibs.pages.clear()
        Dibs.answered.clear()
        Dibs.fields.clear()
        Dibs.open.clear()
        Dibs.chat.draft = ""
        Dibs.chat.about = null
        Dibs.chat.replyTo = null
        Dibs.holdTap = null
    }

    @After
    fun putTheSingletonsBack() {
        Dibs.index = null
        Dibs.chat.draft = ""
        Dibs.chatList.requestScrollToItem(0)
    }

    /** A small index: tasks 230 (working), 231 (needs you), 232 (done) and ideas 23 and 45. */
    private fun linkIndex() = RefIndex.parse(
        """{"tasks":[
          {"n":230,"t":"Make links tappable in the dibs chat","s":"Working","g":"working","p":"tether","a":"links in the chat","f":null},
          {"n":231,"t":"Pick the colour of the launcher tile","s":"Needs you: 1 question","g":"needs","p":"design","a":"launcher tile colour","f":null},
          {"n":232,"t":"Rename the picker rows","s":"Done 2 hours ago","g":"done","p":"tether","a":"rename the rows","f":1700000000}],
          "ideas":[{"n":23,"t":"Groceries by aisle","s":"active"},{"n":45,"t":"A calmer colour for the chat bar","s":"active"}]}""",
    )

    private fun show(view: JSONObject) {
        host = FakeHost(view)
        Dibs.host = host
        // Edge to edge, as DibsActivity draws: the screens pad for the bars and the keyboard themselves.
        WindowCompat.setDecorFitsSystemWindows(compose.activity.window, false)
        compose.setContent { AppTheme { DibsApp() } }
        insets(keyboard = 0)
    }

    /** The phone's bars, and the keyboard open [keyboard] px tall (0: closed). */
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

    private fun shot(name: String) = compose.onRoot().captureRoboImage("build/outputs/roborazzi/$name.png")

    @Test
    fun theChatHasItsBoxUnderALongConversation() {
        show(view(talk = longTalk() + ask(300, "Lend the phone for 30 min?", yesNo(300))))
        compose.onNodeWithText("Message dibs").assertIsDisplayed()
        shot("chat-long")
    }

    @Test
    @Config(qualifiers = "w320dp-h568dp-280dpi")
    fun theChatHasItsBoxOnASmallScreen() {
        show(view(talk = longTalk() + ask(300, "Lend the phone for 30 min?", yesNo(300))))
        compose.onNodeWithText("Message dibs").assertIsDisplayed()
        shot("chat-small")
    }

    @Test
    @Config(qualifiers = "w320dp-h568dp-280dpi")
    fun theHoldChipDrawsDibssWordsAndATapShowsAtOnce() {
        val keeping = holdChip("held", "dibs keeps its reply until you tap Go", "Go", "go-ahead", "primary")
        val wait = holdChip("wait", null, "Wait", "hold", "outline").put("tapped", keeping)
        val v = view(talk = longTalk())
        v.getJSONObject("dibs").getJSONObject("state").put("busy", true).put("hold", wait)
        show(v)
        compose.onNodeWithText("Wait").assertIsDisplayed()
        shot("chat-wait-small")
        compose.onNodeWithText("Wait").performClick()
        compose.waitForIdle()
        assertTrue(host.acts.any { it.first == "hold" })
        compose.onNodeWithText("dibs keeps its reply until you tap Go").assertIsDisplayed()
        compose.onNodeWithText("Go").assertIsDisplayed()
        compose.onNodeWithText("Message dibs").assertIsDisplayed()
        shot("chat-hold-small")
        compose.onNodeWithText("Go").performClick()
        compose.waitForIdle()
        assertTrue(host.acts.any { it.first == "go-ahead" })
    }

    @Test
    @Config(qualifiers = "w320dp-h568dp-280dpi")
    fun aReadyReplyAsksForGoWithoutTheDots() {
        val ready = holdChip("held", "dibs's reply is ready", "Go", "go-ahead", "primary")
        val v = view(talk = longTalk())
        v.getJSONObject("dibs").getJSONObject("state").put("hold", ready)
        show(v)
        compose.onNodeWithText("dibs's reply is ready").assertIsDisplayed()
        compose.onNodeWithText("Go").assertIsDisplayed()
        compose.onAllNodesWithTag("typing-dots").assertCountEquals(0)
        shot("chat-go-ready")
    }

    private fun holdChip(kind: String, note: String?, button: String, action: String, style: String) =
        JSONObject().put("kind", kind).put("note", note ?: JSONObject.NULL).put("button", button).put("action", action).put("style", style)

    @Test
    @Config(qualifiers = "w320dp-h568dp-280dpi")
    fun theChatHasItsBoxWithTheKeyboardOpenOnASmallScreen() {
        show(view(talk = longTalk() + ask(300, "Lend the phone for 30 min?", yesNo(300))))
        // A keyboard of 45% of the screen.
        insets(keyboard = (compose.activity.window.decorView.height * 0.45f).toInt())
        compose.onNodeWithText("Message dibs").assertIsDisplayed()
        shot("chat-small-keyboard")
    }

    @Test
    @Config(qualifiers = "w320dp-h568dp-280dpi")
    fun theBoxStaysWhateverTheKeyboardsHeight() {
        show(view(talk = longTalk() + ask(300, "Lend the phone for 30 min?", yesNo(300))))
        shot("chat-small-bars")
        for (k in listOf(0.3f, 0.45f, 0.55f)) {
            insets(keyboard = (compose.activity.window.decorView.height * k).toInt())
            compose.onNodeWithText("Message dibs").assertIsDisplayed()
        }
        shot("chat-small-keyboard-55")
    }

    @Test
    fun theTasksTabSaysWhatItsBadgeCounts() {
        val gb = 1_073_741_824L
        val v = view(talk = longTalk())
        val d = v.getJSONObject("dibs")
        d.getJSONObject("state").put("laptop", JSONObject().put("mem_used", 11 * gb).put("mem_total", 16 * gb).put("load", 6.2)
            .put("cores", 16).put("builds", 2).put("waiting", 11).put("agents", 14).put("name", "Server"))
        d.put("yours", JSONArray()
            .put(yours(1, "Cut phone notification clutter (the user", "working"))
            .put(yours(2, "PLAN ONLY", "done", asked = "PLAN ONLY: plan how dibs spends less usage", unread = true))
            .put(yours(3, "Lend the phone?", "needs")))
        show(v)
        Dibs.tab = "tasks"
        compose.waitForIdle()
        compose.onNodeWithText("2 FOR YOU: 1 TO READ, 1 ASKING YOU").assertIsDisplayed()
        compose.onNodeWithText("Cut phone notification clutter").assertIsDisplayed()
        compose.onNodeWithText("Plan how dibs spends less usage").assertIsDisplayed()
        shot("tasks")
    }

    @Test
    fun theTaskPageOffersItsFullStory() {
        show(storyView(JSONObject().put("state", "ready").put("ts", NOW - 600).put("stale", true).put("have", true).put("by", "writer")))
        Dibs.pages += Page.Task(31)
        compose.waitForIdle()
        compose.onNodeWithText("Full story").assertIsDisplayed()
        compose.onNodeWithText("the task moved on since", substring = true).assertIsDisplayed()
        shot("task-story-row")
    }

    @Test
    fun theFullStoryReads() {
        show(storyView(JSONObject().put("state", "ready").put("ts", NOW - 600).put("stale", true).put("have", true).put("by", "writer")))
        host.files["story-31-"] = storyFile()
        Dibs.pages += Page.Story(31)
        compose.waitForIdle()
        compose.onNodeWithText("Recap: one entry per finished job").assertIsDisplayed()
        compose.onNodeWithText("The task moved on since this was written.").assertIsDisplayed()
        compose.onNodeWithText("Ask dibs about this").assertIsDisplayed()
        assertTrue("a kept one isn't asked for again", host.acts.none { it.first == "fetch" })
        shot("story")
    }

    @Test
    @Config(qualifiers = "w320dp-h568dp-280dpi")
    fun theFullStorysButtonsFitASmallScreen() {
        show(storyView(JSONObject().put("state", "ready").put("ts", NOW - 600).put("have", true).put("by", "agent")))
        host.files["story-31-"] = storyFile()
        Dibs.pages += Page.Story(31)
        compose.waitForIdle()
        // The story file loads off the main thread: wait for it, as a slow machine takes longer.
        compose.waitUntil(10_000) { compose.onAllNodes(hasText("Start a follow-up")).fetchSemanticsNodes().isNotEmpty() }
        for (b in listOf("Show the conversation", "Start a follow-up", "Ask dibs about this")) compose.onNodeWithText(b).assertIsDisplayed()
        shot("story-small")
    }

    @Test
    fun aStoryNeverWrittenIsAskedForAndWritten() {
        show(storyView(null))
        Dibs.pages += Page.Story(31)
        compose.waitForIdle()
        compose.onNodeWithText("dibs is writing it").assertIsDisplayed()
        val (_, value) = host.acts.single { it.first == "fetch" }
        assertEquals("story", value!!.getString("what"))
        assertEquals(31L, value.getLong("task"))
        shot("story-writing")
    }

    @Test
    fun aStoryBeingWrittenIsNeverFetched() {
        show(storyView(JSONObject().put("state", "writing").put("since", NOW - 60).put("by", "writer")))
        Dibs.pages += Page.Story(31)
        compose.waitForIdle()
        compose.onNodeWithText("dibs is writing it").assertIsDisplayed()
        assertTrue("entering must not start a write", host.acts.none { it.first == "fetch" || it.first == "story" })
    }

    @Test
    fun aFailedStoryIsNeverFetched() {
        show(storyView(JSONObject().put("state", "failed")))
        Dibs.pages += Page.Story(31)
        compose.waitForIdle()
        compose.onNodeWithText("It couldn't be written").assertIsDisplayed()
        assertTrue("only Try again writes it", host.acts.none { it.first == "fetch" || it.first == "story" })
    }

    @Test
    fun aReadyNoteOpensTheStoryAndTheChatSaysWhatItsAbout() {
        val v = storyView(JSONObject().put("state", "ready").put("ts", NOW - 600).put("have", true))
        v.getJSONObject("dibs").put("talk", JSONArray(longTalk()).put(
            JSONObject().put("id", "s99").put("n", 99).put("who", "dibs").put("note", true).put("ts", NOW - 30)
                .put("text", "The full story of “Recap” is ready.").put("open", JSONObject().put("story", 31)),
        ))
        show(v)
        compose.onNodeWithText("Read it").assertIsDisplayed().performClick()
        compose.waitForIdle()
        assertEquals(Page.Story(31), Dibs.pages.lastOrNull())
        Dibs.pages.clear()
        Dibs.chat.about = About(31, "ask", "Recap", "It tried folding by day first, which hid the newest entry.")
        compose.waitForIdle()
        compose.onNodeWithText("About a part of the full story of #31 Recap").assertIsDisplayed()
        shot("chat-about-story")
    }

    @Test
    fun longTextsWrapAndAreNeverCut() {
        show(longView())
        Dibs.tab = "tasks"
        compose.waitForIdle()
        compose.onNodeWithText(LONG_TITLE).assertIsDisplayed()
        noEllipsis()
        shot("tasks-long")
        // The usage windows and the laptop's lend card live on the dibs page now.
        Dibs.pages += Page.Status
        compose.waitForIdle()
        compose.onAllNodes(hasText("resets", substring = true), useUnmergedTree = true).assertCountEquals(2)
        noEllipsis()
        shot("dibs-page-long")
        Dibs.pages.clear()
        compose.waitForIdle()
        Dibs.pages += Page.Task(41)
        compose.waitForIdle()
        noEllipsis()
        shot("task-long")
    }

    @Test
    @Config(qualifiers = "w320dp-h568dp-280dpi")
    fun longTextsWrapOnASmallScreen() {
        show(longView())
        Dibs.tab = "tasks"
        compose.waitForIdle()
        noEllipsis()
        shot("tasks-long-small")
        Dibs.pages += Page.Task(41)
        compose.waitForIdle()
        noEllipsis()
        shot("task-long-small")
    }

    @Test
    fun theTasksTabDrawsDibssBoard() {
        show(boardView())
        Dibs.tab = "tasks"
        compose.waitForIdle()
        for (h in listOf("ACTIVE · 1", "BACKLOG · 2")) compose.onNodeWithText(h).assertIsDisplayed()
        compose.onNodeWithText("DONE · 12").assertIsDisplayed()
        compose.onNodeWithText(LONG_TITLE).assertIsDisplayed()
        compose.onNodeWithText("#31").assertIsDisplayed()
        compose.onNodeWithText("Full story").assertIsDisplayed()
        // One button on each card's face: Park on the Active card, Start on both Backlog cards.
        compose.onAllNodes(hasText("Park")).assertCountEquals(1)
        compose.onAllNodes(hasText("Start")).assertCountEquals(2)
        // Done is folded, and the old project groups aren't drawn.
        compose.onAllNodes(hasText("Lend toggles")).assertCountEquals(0)
        compose.onAllNodes(hasText("TETHER")).assertCountEquals(0)
        noEllipsis()
        shot("tasks-board")
        compose.onNodeWithText("DONE · 12").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Lend toggles").assertIsDisplayed()
    }

    @Test
    fun theBacklogHasADropBoxAndShowsWhatDibsIsReading() {
        val v = boardView()
        val backlog = v.getJSONObject("dibs").getJSONObject("board").getJSONArray("columns").getJSONObject(1)
        val cards = backlog.getJSONArray("cards")
        val drop = JSONObject().put("key", "drop:bbbbbbbbbbbbbbbb0000000000000002").put("n", JSONObject.NULL).put("title", "Make the app dark")
            .put("state_words", "dibs asks: Which app: the phone or the laptop?").put("now", "").put("tags", JSONArray()).put("story", JSONObject.NULL)
            .put("actions", JSONArray()).put("primary", JSONObject.NULL).put("open", "idea:46")
        val all = JSONArray().put(drop)
        for (i in 0 until cards.length()) all.put(cards.getJSONObject(i))
        backlog.put("cards", all).put("backlog_box", JSONObject().put("placeholder", "Add to the Backlog: an idea, a problem, a task"))
        show(v)
        Dibs.tab = "tasks"
        compose.waitForIdle()
        compose.onNodeWithText("Add to the Backlog: an idea, a problem, a task").assertIsDisplayed()
        compose.onNodeWithText("Make the app dark").assertIsDisplayed()
        compose.onNodeWithText("dibs asks: Which app: the phone or the laptop?").assertIsDisplayed()
        // The drop has no button; the two real cards keep theirs.
        compose.onAllNodes(hasText("Start")).assertCountEquals(2)
        shot("tasks-backlog-drop")
    }

    @Test
    @Config(qualifiers = "w320dp-h568dp-280dpi")
    fun theBoardOnASmallScreen() {
        show(boardView())
        Dibs.tab = "tasks"
        compose.waitForIdle()
        noEllipsis()
        shot("tasks-board-small")
    }

    @Test
    fun aBoardCardsMenuOffersOnlyItsActions() {
        show(boardView())
        Dibs.tab = "tasks"
        compose.waitForIdle()
        compose.onNodeWithText(LONG_TITLE).performTouchInput { longClick() }
        compose.waitForIdle()
        for (w in listOf("Stop", "Full story", "Move down")) compose.onAllNodes(hasText(w)).assertCountEquals(if (w == "Full story") 2 else 1)
        for (w in listOf("Resume", "Put on hold", "Delete", "Move up", "Move to Backlog", "Start now")) compose.onAllNodes(hasText(w)).assertCountEquals(0)
        // Its primary (Park) stays on the face only, once; the menu does not repeat it.
        compose.onAllNodes(hasText("Park")).assertCountEquals(1)
        // Stop asks again; only the second tap sends it.
        compose.onNodeWithText("Stop").performClick()
        compose.waitForIdle()
        assertTrue(host.acts.none { it.first == Dibs.TASK_ACT })
        compose.onNodeWithText("Stop it?").performClick()
        compose.waitForIdle()
        val (_, stop) = host.acts.single { it.first == Dibs.TASK_ACT }
        assertEquals("task:31", stop!!.getString("key"))
        assertEquals("stop", stop.getString("act"))
        // An idea has no page: a tap opens its menu.
        compose.onNodeWithText("A widget that shows the board on the home screen").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Move up").performClick()
        compose.waitForIdle()
        val move = host.acts.last().second!!
        assertEquals("idea:7", move.getString("key"))
        assertEquals("up", move.getString("act"))
        assertTrue(Dibs.pages.isEmpty())
    }

    @Test
    fun deletingACardSaysWhatItLosesAndConfirms() {
        show(boardView())
        Dibs.tab = "tasks"
        compose.waitForIdle()
        compose.onNodeWithText("Pick the phone's notification sound").performTouchInput { longClick() }
        compose.waitForIdle()
        compose.onAllNodes(hasText(LOSES)).assertCountEquals(0)
        compose.onNodeWithText("Delete").performClick()
        compose.waitForIdle()
        assertTrue(host.acts.none { it.first == Dibs.TASK_ACT })
        compose.onNodeWithText(LOSES).assertExists()
        shot("tasks-board-delete")
        compose.onNodeWithText("Delete it?").performClick()
        compose.waitForIdle()
        val (_, del) = host.acts.single { it.first == Dibs.TASK_ACT }
        assertEquals("task:30", del!!.getString("key"))
        assertEquals("delete", del.getString("act"))
        assertTrue("dibs applies a delete only with its confirm", del.getBoolean("confirm"))
    }

    /** A board as dibs sends it: Active with one card (a long title, tags and a story), Backlog with an idea and a parked task, Done folded. */
    private fun boardView(): JSONObject {
        val v = view(talk = longTalk())
        val d = v.getJSONObject("dibs")
        d.put("yours", JSONArray().put(yours(31, LONG_TITLE, "working").put("busy", true)).put(yours(29, "Lend toggles", "done")))
        fun card(key: String, n: Long?, title: String, state: String, now: String = "", tags: List<String> = emptyList(), actions: List<String>, story: JSONObject? = null) =
            JSONObject().put("key", key).put("n", n ?: JSONObject.NULL).put("title", title).put("state_words", state).put("now", now)
                .put("tags", JSONArray(tags)).put("story", story ?: JSONObject.NULL).put("actions", JSONArray(actions))
        d.put("board", JSONObject()
            .put("columns", JSONArray()
                .put(JSONObject().put("key", "active").put("title", "Active").put("cards", JSONArray().put(
                    card("task:31", 31, LONG_TITLE, "working", "Running the screen tests again after the header change",
                        listOf("work saved"), listOf("park", "stop", "story", "down"), JSONObject().put("state", "ready").put("have", true))
                        .put("primary", "park").put("lane", "running"),
                )))
                .put(JSONObject().put("key", "backlog").put("title", "Backlog").put("cards", JSONArray()
                    .put(card("idea:7", null, "A widget that shows the board on the home screen", "an idea", actions = listOf("start", "up", "down", "delete")).put("primary", "start"))
                    .put(card("task:30", 30, "Pick the phone's notification sound", "you parked it", tags = listOf("work saved"), actions = listOf("start", "start_now", "delete"))
                        .put("primary", "start").put("delete_text", LOSES)))))
            .put("done", JSONObject().put("count", 12).put("cards", JSONArray().put(card("task:29", 29, "Lend toggles", "done", actions = emptyList())))))
        return v
    }

    /** No "…" in anything drawn (the user's rule): the app's own words never end in one, and nothing is cut. */
    private fun noEllipsis() {
        compose.onAllNodes(hasText("…", substring = true), useUnmergedTree = true).assertCountEquals(0)
    }

    /** A view whose texts are as long as they come: the week's usage with its day, a long title, line and ask. */
    private fun longView(): JSONObject {
        val v = view(talk = longTalk())
        val d = v.getJSONObject("dibs")
        d.getJSONObject("state").getJSONObject("limits").put("windows", JSONArray()
            .put(JSONObject().put("name", "five_hour").put("pct", 87.0).put("resets", NOW + 3 * 3600))
            .put(JSONObject().put("name", "seven_day").put("pct", 93.0).put("resets", NOW + 3 * 86400)))
        d.getJSONObject("lends").put("laptop", JSONObject().put("lent", true).put("text", "Until 15:40, rami-0f is on it with two builds").put("action", "laptop-back"))
        val long = yours(41, LONG_TITLE, "working")
            .put("line", "Rebuilding the release APK after moving the board's parsing into Payload.kt, then running every screen test again")
            .put("asked", "tether: $LONG_TITLE. Then build the release APK and look at every screenshot yourself, so nothing is cut and nothing overlaps.")
            .put("report", "Every text wraps now.")
        d.put("yours", JSONArray().put(long).put(yours(42, "Lend the phone?", "needs")))
        return v
    }

    private fun storyView(story: JSONObject?): JSONObject {
        val v = view(talk = longTalk())
        val t = yours(31, "Recap", "done").put("report", "Recap shows one entry per job.")
        if (story != null) t.put("story", story)
        v.getJSONObject("dibs").put("yours", JSONArray().put(t))
        return v
    }

    private fun storyFile(): File = File.createTempFile("story-31-", ".md").apply {
        deleteOnExit()
        writeText(
            """
            # Recap: one entry per finished job

            You asked for **Recap** to stop repeating itself: every finished job showed up three times.

            ## What it tried

            It first folded the lines by day, which hid the newest entry. Then it keyed them by `task`, which held.

            - Folding by day: dropped, it hid the newest line.
            - Keying by task: kept.

            ## What's left

            Nothing on the phone; dibs's side still writes two titles.
            """.trimIndent(),
        )
    }

    /** The usage of [pct] percent in the first window. */
    private fun usageAt(v: JSONObject, pct: Double): JSONObject {
        v.getJSONObject("dibs").getJSONObject("state").getJSONObject("limits").getJSONArray("windows")
            .getJSONObject(0).put("pct", pct)
        return v
    }

    @Test
    fun theBarSaysNothingAboutUsageUntilAWindowWarns() {
        val v = usageAt(view(talk = longTalk()), 17.0)
        v.getJSONObject("dibs").getJSONObject("state").getJSONObject("limits").getJSONArray("windows").getJSONObject(1).put("pct", 20.0)
        show(v)
        compose.onAllNodes(hasText("5h", substring = true)).assertCountEquals(0)
        compose.onNodeWithText("dibs").assertIsDisplayed()
    }

    @Test
    fun theBarShowsTheWarningWindowWithoutItsResetTime() {
        show(usageAt(view(talk = longTalk()), 88.0))
        compose.onNodeWithText("5h\u00A088%").assertIsDisplayed()
        compose.onAllNodes(hasText("resets", substring = true)).assertCountEquals(0)
        shot("bar-warning")
    }

    @Test
    fun aLentLaptopShowsAsAPillAndAnUnlentPhoneDoesNot() {
        show(view(talk = longTalk()))
        compose.onNodeWithContentDescription("Laptop lent to dibs", useUnmergedTree = true).assertExists()
        compose.onNodeWithContentDescription("Phone lent to dibs", useUnmergedTree = true).assertDoesNotExist()
        // No lend cards on the chat any more.
        compose.onAllNodes(hasText("Until 15:40", substring = true)).assertCountEquals(0)
    }

    @Test
    fun aTapOnTheBarOpensTheDibsPageWithBothLendCardsAndEveryUsageLine() {
        show(view(talk = longTalk()))
        compose.onNodeWithContentDescription("dibs, laptop and phone", substring = true).performClick()
        compose.waitForIdle()
        assertEquals(Page.Status, Dibs.pages.last())
        compose.onNodeWithText("Phone").assertIsDisplayed()
        compose.onNodeWithText("Laptop lent to dibs").assertIsDisplayed()
        compose.onNodeWithText("Until 15:40").assertIsDisplayed()
        compose.onNodeWithText("Laptop's memory and processor").assertIsDisplayed()
        compose.onNodeWithText("5h\u00A087%", substring = true).assertIsDisplayed()
        compose.onNodeWithText("7d\u00A063%", substring = true).assertIsDisplayed()
        shot("dibs-page")
    }

    @Test
    fun theChatKeepsMostOfTheScreen() {
        show(view(talk = longTalk()))
        val root = compose.onRoot().fetchSemanticsNode().boundsInRoot.height
        val chat = compose.onNodeWithTag("conversation").fetchSemanticsNode().boundsInRoot.height
        assertTrue("chat ${chat / root}", chat / root > 0.65f)
        shot("chat-share")
    }

    @Test
    fun aHeldDibsStillShowsTheDotsWhileItWorks() {
        val held = JSONObject().put("kind", "held").put("note", "dibs keeps its reply until you tap Go")
            .put("button", "Go").put("action", "go-ahead").put("style", "primary")
        val v = view(talk = longTalk())
        v.getJSONObject("dibs").getJSONObject("state").put("busy", true).put("line", "Reading the board")
        show(v)
        compose.onAllNodesWithTag("typing-dots").assertCountEquals(1)
        v.getJSONObject("dibs").getJSONObject("state").put("hold", held)
        host.view.value = JSONObject(v.toString())
        compose.waitForIdle()
        compose.onAllNodesWithTag("typing-dots").assertCountEquals(1)
        compose.onNodeWithText("Reading the board").assertIsDisplayed()
        // Its reply is ready (not busy any more): nothing is going on.
        v.getJSONObject("dibs").getJSONObject("state").put("busy", false)
        host.view.value = JSONObject(v.toString())
        compose.waitForIdle()
        compose.onAllNodesWithTag("typing-dots").assertCountEquals(0)
    }

    @Test
    fun aSwipeToTheRightRepliesToALineAndAVerticalOneDoesNot() {
        show(view(talk = longTalk()))
        compose.onNodeWithText("Line 27 from me").performTouchInput { swipeDown() }
        compose.waitForIdle()
        assertEquals(null, Dibs.chat.replyTo)
        compose.onNodeWithText("Line 27 from me").performTouchInput { swipeRight(startX = left + 10f, endX = left + 400f) }
        compose.waitForIdle()
        assertEquals("u-27", Dibs.chat.replyTo?.id)
        compose.onNodeWithText("Replying to yourself").assertIsDisplayed()
        shot("chat-reply-chip")
        // One at a time: an about chip replaces the reply.
        Dibs.chat.aboutIs(About(31, "ask", "Recap"))
        assertEquals(null, Dibs.chat.replyTo)
        Dibs.chat.replyIs(longTalk().let { TalkLine("s1", 1, false, "x", null, false, 0, emptyList(), null) })
        assertEquals(null, Dibs.chat.about)
    }

    @Test
    fun sendingAReplyPutsItsLineInTheSayAndClearsTheChip() {
        show(view(talk = longTalk()))
        Dibs.chat.replyIs(TalkLine("s28", 28, false, "dibs's line 28", null, false, 0, emptyList(), null))
        compose.waitForIdle()
        Dibs.chat.draft = "yes, that one"
        Dibs.chat.send()
        compose.waitForIdle()
        val say = host.acts.last { it.first == "say" }.second!!
        assertEquals(28L, say.getJSONObject("reply").getLong("n"))
        assertEquals(null, Dibs.chat.replyTo)
        compose.onAllNodesWithText("Replying to dibs").assertCountEquals(0)
    }

    @Test
    fun aReplyDrawsItsQuoteAndATapJumpsToTheQuotedLine() {
        val talk = longTalk().toMutableList()
        talk[29] = JSONObject(talk[29].toString()).put("reply", JSONObject().put("n", 3).put("who", "user").put("text", "Line 3 from me"))
        show(view(talk = talk))
        compose.onNodeWithText("You").assertIsDisplayed()
        compose.onNodeWithText("Line 3 from me").performClick()
        compose.waitForIdle()
        // The tap scrolled the quoted line into view (it was far above the newest).
        compose.onNodeWithText("Line 3 from me", useUnmergedTree = true).assertIsDisplayed()
        shot("chat-reply-quote")
    }

    @Test
    fun aReplyAfterAFollowUpLeavesNoBareFollowUpInTheBox() {
        show(view(talk = longTalk()))
        Dibs.chat.draft = Dibs.FOLLOW_UP
        Dibs.chat.aboutIs(About(31, "follow", "Recap"))
        Dibs.chat.replyIs(TalkLine("s28", 28, false, "x", null, false, 0, emptyList(), null))
        assertEquals("", Dibs.chat.draft)
        assertTrue(!Dibs.chat.canSend)
    }

    @Test
    fun selectTextOpensTheLineWholeInASheet() {
        show(view(talk = longTalk()))
        compose.onNodeWithText("Line 27 from me").performTouchInput { longClick() }
        compose.onNodeWithText("Select text").assertIsDisplayed().performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Copy all").assertIsDisplayed()
        shot("chat-select-text")
    }

    @Test
    fun aWaitingCardOpensItsTaskOnATapOnItsWords() {
        val v = view(talk = longTalk(), questions = listOf(JSONObject().put("id", 7).put("title", "Queue the cleanup now?").put("why", "It is small")
            .put("from", "agent").put("ts", NOW - 60).put("kind", "question").put("task", 41).put("actions", JSONArray())))
        v.getJSONObject("dibs").put("yours", JSONArray().put(yours(41, "Cleanup", "needs")))
        show(v)
        Dibs.tab = "waiting"
        compose.waitForIdle()
        compose.onNodeWithText("Queue the cleanup now?").performClick()
        compose.waitForIdle()
        assertEquals(Page.Task(41), Dibs.pages.last())
    }

    @Test
    fun aWaitingCardReadsItInFullWithoutAnsweringIt() {
        val v = view(talk = longTalk(), questions = listOf(JSONObject().put("id", 9).put("title", "Research done: Memory").put("why", "What to buy")
            .put("from", "dibs").put("ts", NOW - 60).put("kind", "question")
            .put("actions", JSONArray().put(JSONObject().put("id", "approve").put("label", "Got it")))
            .put("read", JSONObject().put("label", "Read it in full").put("task", 42).put("url", JSONObject.NULL))))
        show(v)
        Dibs.tab = "waiting"
        compose.waitForIdle()
        compose.onNodeWithText("Read it in full").assertIsDisplayed()
        shot("waiting-read-it-in-full")
        compose.onNodeWithText("Read it in full").performClick()
        compose.waitForIdle()
        assertEquals(Page.Task(42), Dibs.pages.last())
        assertTrue(Dibs.answered.isEmpty())
    }

    @Test
    fun aDeviceLoginCardIsWholeWithItsLinkAndACopyButton() {
        val text = "Let the home server read your GitHub: enter code 070B-16D2 at github.com/login/device, then tap Done"
        val v = view(talk = longTalk(), questions = listOf(JSONObject().put("id", 722).put("title", text)
            .put("why", "Step 1 of the server move needs it to fetch your projects. The code expires about 15 minutes after it was made.")
            .put("from", "dibs").put("ts", NOW - 60).put("kind", "question")
            .put("actions", JSONArray().put(JSONObject().put("id", "a722").put("label", "Done, I approved it").put("style", "primary"))
                .put(JSONObject().put("id", "d722").put("label", "Don't sign it in")))))
        show(v)
        Dibs.tab = "waiting"
        compose.waitForIdle()
        compose.onNodeWithText(text).assertIsDisplayed()
        noEllipsis()
        shot("waiting-device-login")
        compose.onNodeWithText("Copy", substring = true).performClick()
        compose.waitForIdle()
        val clip = compose.activity.getSystemService(ClipboardManager::class.java).primaryClip
        assertEquals("070B-16D2", clip?.getItemAt(0)?.text?.toString())
        compose.onNodeWithText("Copied", substring = true).assertIsDisplayed()
        // The address and the code are links in the text itself.
        val links = linkified(text, onCode = {}).getLinkAnnotations(0, text.length).map { it.item }
        assertTrue(links.any { it is LinkAnnotation.Url && it.url == "https://github.com/login/device" })
        assertTrue(links.any { it is LinkAnnotation.Clickable && it.tag == "code:070B-16D2" })
    }

    @Test
    fun theTasksTabCountsItsSectionsAndExplainsAFullLaptop() {
        val v = view(talk = longTalk())
        v.getJSONObject("dibs").put("yours", JSONArray().put(yours(1, "Cleanup", "working")))
        v.getJSONObject("dibs").put("board", JSONObject()
            .put("columns", JSONArray()
                .put(JSONObject().put("key", "active").put("title", "Active").put("cards", JSONArray().put(JSONObject().put("key", "task:1").put("n", 1).put("title", "Cleanup")
                    .put("state_words", "Waiting its turn").put("now", "").put("tags", JSONArray()).put("actions", JSONArray()).put("primary", "park").put("lane", "waiting")))))
            .put("done", JSONObject().put("count", 0).put("cards", JSONArray()))
            .put("room", JSONObject().put("room", 0).put("line", "No room for another task: the laptop is at 14 of 15.6 GB")))
        show(v)
        Dibs.tab = "tasks"
        compose.waitForIdle()
        compose.onNodeWithText("ACTIVE · 1").assertIsDisplayed()
        compose.onNodeWithText("No room for another task", substring = true).assertIsDisplayed()
        shot("tasks-queue")
    }

    @Test
    fun theTasksTabSaysFirstWhenTheQueueIsOnHold() {
        val v = view(talk = longTalk())
        v.getJSONObject("dibs").put("yours", JSONArray().put(yours(1, "Cleanup", "working")))
        v.getJSONObject("dibs").put("board", JSONObject()
            .put("columns", JSONArray()
                .put(JSONObject().put("key", "next").put("title", "Queue").put("cards", JSONArray().put(JSONObject().put("key", "task:1").put("n", 1).put("title", "Cleanup")
                    .put("state_words", "Waiting its turn").put("now", "").put("tags", JSONArray()).put("actions", JSONArray())))))
            .put("done", JSONObject().put("count", 0).put("cards", JSONArray()))
            .put("hold", JSONObject().put("why", "move").put("line", "On hold while dibs moves to the server: nothing new starts")))
        show(v)
        Dibs.tab = "tasks"
        compose.waitForIdle()
        compose.onNodeWithText("On hold while dibs moves to the server: nothing new starts").assertIsDisplayed()
        compose.onNodeWithText("QUEUE · 1").assertIsDisplayed()
        shot("tasks-queue-on-hold")
    }

    @Test
    fun aQueuedCardStartsNowFromItsMenu() {
        val v = view(talk = longTalk())
        v.getJSONObject("dibs").put("yours", JSONArray())
        v.getJSONObject("dibs").put("board", JSONObject()
            .put("columns", JSONArray()
                .put(JSONObject().put("key", "next").put("title", "Queue").put("cards", JSONArray().put(JSONObject().put("key", "task:8").put("n", 8).put("title", "Sort the photos")
                    .put("state_words", "next in line").put("now", "").put("tags", JSONArray()).put("actions", JSONArray().put("start_now").put("to_later").put("delete"))))))
            .put("done", JSONObject().put("count", 0).put("cards", JSONArray())))
        show(v)
        Dibs.tab = "tasks"
        compose.waitForIdle()
        compose.onNodeWithText("Sort the photos").performTouchInput { longClick() }
        compose.waitForIdle()
        // No confirm step: the tap is the user's word for now.
        compose.onNodeWithText("Start now").performClick()
        compose.waitForIdle()
        val (_, act) = host.acts.single { it.first == Dibs.TASK_ACT }
        assertEquals("task:8", act!!.getString("key"))
        assertEquals("start_now", act.getString("act"))
    }

    /** A dibs that still sends now/next/later reads and draws too, the room line over its "next" column. */
    @Test
    fun anOldDibsBoardWithThreeColumnsStillDraws() {
        val v = view(talk = longTalk())
        v.getJSONObject("dibs").put("yours", JSONArray().put(yours(1, "Cleanup", "working")))
        fun col(key: String, title: String, title1: String) = JSONObject().put("key", key).put("title", title).put("cards", JSONArray().put(JSONObject().put("key", "task:$title1").put("n", title1.toInt())
            .put("title", "Task $title1").put("state_words", "x").put("now", "").put("tags", JSONArray()).put("actions", JSONArray().put("to_later").put("resume"))))
        v.getJSONObject("dibs").put("board", JSONObject()
            .put("columns", JSONArray().put(col("now", "Working now", "1")).put(col("next", "Queue", "2")).put(col("later", "Later", "3")))
            .put("done", JSONObject().put("count", 0).put("cards", JSONArray()))
            .put("room", JSONObject().put("room", 0).put("line", "No room for another task: the laptop is at 14 of 15.6 GB")))
        show(v)
        Dibs.tab = "tasks"
        compose.waitForIdle()
        for (h in listOf("WORKING NOW · 1", "QUEUE · 1", "LATER · 1")) compose.onNodeWithText(h).assertIsDisplayed()
        compose.onNodeWithText("No room for another task", substring = true).assertIsDisplayed()
        // No primary from an old dibs: no button on the face.
        compose.onAllNodes(hasText("Start")).assertCountEquals(0)
        compose.onAllNodes(hasText("Park")).assertCountEquals(0)
    }

    /** The Start button on a Backlog card's face sends task-act start for that card, and Park the same for an Active one. */
    @Test
    fun theButtonOnACardsFaceSendsItsPrimary() {
        show(boardView())
        Dibs.tab = "tasks"
        compose.waitForIdle()
        compose.onAllNodes(hasText("Start"))[1].performClick()
        compose.waitForIdle()
        val (_, start) = host.acts.single { it.first == Dibs.TASK_ACT }
        assertEquals("task:30", start!!.getString("key"))
        assertEquals("start", start.getString("act"))
        compose.onNodeWithText("Park").performClick()
        compose.waitForIdle()
        val (_, park) = host.acts.last { it.first == Dibs.TASK_ACT }
        assertEquals("task:31", park!!.getString("key"))
        assertEquals("park", park.getString("act"))
    }

    @Test
    fun linksInTheChatAreDrawnAsLinks() {
        Dibs.index = linkIndex()
        val talk = longTalk().dropLast(3) + listOf(
            JSONObject().put("id", "s90").put("n", 90).put("who", "dibs").put("ts", NOW - 300)
                .put("text", "That was made from #230 and idea 45. #999 is not one I know."),
            JSONObject().put("id", "u-91").put("n", 91).put("who", "user").put("ts", NOW - 200)
                .put("text", "Is #231 waiting on me?"),
            JSONObject().put("id", "s92").put("n", 92).put("who", "dibs").put("ts", NOW - 100)
                .put("text", "Yes, it needs a colour. Hold a link for a card."),
        )
        show(view(talk = talk))
        compose.onNodeWithText("Is #231 waiting on me?").assertIsDisplayed()
        compose.onNodeWithText("That was made from #230", substring = true).assertIsDisplayed()
        shot("links-chat")
    }

    @Test
    fun theLinkCardSaysWhatItIsAndOffersOpen() {
        Dibs.index = linkIndex()
        var opened = 0
        compose.setContent {
            AppTheme {
                androidx.compose.foundation.layout.Box(
                    androidx.compose.ui.Modifier.fillMaxSize().background(com.kivan.tether.dibs.ui.theme.Palette.Bg).padding(24.dp),
                ) { RefCard(RefKind.TASK, 231, onOpen = { opened++ }, onDismiss = {}) }
            }
        }
        compose.onNodeWithText("#231 · design").assertIsDisplayed()
        compose.onNodeWithText("Pick the colour of the launcher tile").assertIsDisplayed()
        compose.onNodeWithText("Needs you: 1 question").assertIsDisplayed()
        shot("links-card")
        compose.onNodeWithText("Open").performClick()
        assertEquals(1, opened)
    }

    @Test
    fun aTaskPageListsItsLinkedIdeasAndRelatedTasks() {
        Dibs.index = linkIndex()
        val v = view(talk = longTalk())
        val t = yours(31, "Make links tappable", "working")
            .put("state_words", "Working").put("group", "working")
            .put("links", JSONArray()
                .put(JSONObject().put("kind", "idea").put("n", 45).put("title", "A calmer colour for the chat bar").put("state", "Idea").put("why", "Made from"))
                .put(JSONObject().put("kind", "task").put("n", 230).put("title", "Make links tappable in the dibs chat").put("state", "Working").put("why", "Waits for"))
                .put(JSONObject().put("kind", "task").put("n", 232).put("title", "Rename the picker rows").put("state", "Done 2 hours ago").put("why", "Related")))
        v.getJSONObject("dibs").put("yours", JSONArray().put(t))
        show(v)
        Dibs.pages += Page.Task(31)
        compose.waitForIdle()
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("Related tasks", ignoreCase = true))
        compose.onNodeWithText("Linked ideas", ignoreCase = true).assertIsDisplayed()
        compose.onNodeWithText("Related tasks", ignoreCase = true).assertIsDisplayed()
        compose.onNodeWithText("A calmer colour for the chat bar").assertIsDisplayed()
        shot("links-task-page")
    }

    @Test
    fun hashInTheBoxOpensThePickerWithWhatMatches() {
        Dibs.index = linkIndex()
        show(view(talk = longTalk()))
        Dibs.chat.draft = "#23"
        compose.waitForIdle()
        compose.onNodeWithTag("ref-picker").assertIsDisplayed()
        compose.onNodeWithText("Pick the colour of the launcher tile").assertIsDisplayed()
        compose.onNodeWithText("Groceries by aisle").assertIsDisplayed()
        shot("links-picker")
        compose.onNodeWithText("Rename the picker rows").performClick()
        compose.waitForIdle()
        assertEquals("#232 ", Dibs.chat.draft)
        compose.onAllNodesWithTag("ref-picker").assertCountEquals(0)
    }

    @Test
    fun thePickerSaysSoWhenNothingMatchesAndItsCrossClosesIt() {
        Dibs.index = linkIndex()
        show(view(talk = longTalk()))
        Dibs.chat.draft = "#zzz"
        compose.waitForIdle()
        compose.onNodeWithText("No task or idea matches").assertIsDisplayed()
        compose.onNodeWithContentDescription("Close the list").performClick()
        compose.waitForIdle()
        compose.onAllNodesWithText("No task or idea matches").assertCountEquals(0)
    }

    private fun yours(id: Long, title: String, state: String, asked: String = title, unread: Boolean = false) = JSONObject()
        .put("id", id).put("title", title).put("name", "t$id").put("project", "tether").put("state", state)
        .put("ts", NOW - id * 60).put("started", NOW - 3600).put("asked", asked).put("unread", unread).put("line", "What it did last")

    private fun yesNo(q: Long) = JSONArray()
        .put(JSONObject().put("id", "a$q").put("label", "Approve").put("style", "primary"))
        .put(JSONObject().put("id", "d$q").put("label", "Deny").put("style", ""))
        .put(JSONObject().put("id", "x$q").put("label", "Dismiss").put("style", "plain"))

    private fun ask(q: Long, text: String, actions: JSONArray, reply: Boolean = false) = listOf(
        JSONObject().put("id", "s$q").put("n", q).put("who", "dibs").put("text", text).put("ts", NOW - 60)
            .put("ask", JSONObject().put("q", q).put("actions", actions).apply { if (reply) put("reply", "r$q") }),
    )

    private fun longTalk() = (0 until 30).map { i ->
        val mine = i % 3 == 0
        JSONObject().put("id", if (mine) "u-$i" else "s$i").put("n", i).put("who", if (mine) "user" else "dibs")
            .put("text", if (mine) "Line $i from me" else "dibs's line $i, a little longer so it wraps onto a second line on the phone.")
            .put("ts", NOW - 3600 + i * 100)
    }

    @Test
    fun theWaitingBadgeCountsWhatTheTabLists() {
        // dibs's own count says 2, but nothing is listed: no badge, as the tab says (the user's bug, word 784).
        val v = view(talk = emptyList())
        v.getJSONObject("dibs").getJSONObject("badges").put("waiting", 2)
        show(v)
        Dibs.tab = "waiting"
        compose.onNodeWithText("Nothing waiting on you.").assertIsDisplayed()
        compose.onNodeWithTag("badge-waiting", useUnmergedTree = true).assertDoesNotExist()
        // Two listed, one answered here: the badge and the list both say 1.
        val q = { id: Int -> JSONObject().put("id", id).put("title", "Question $id?").put("why", "").put("from", "dibs").put("ts", NOW).put("kind", "question") }
        Dibs.answered["q2"] = "Yes"
        host.view.value = view(talk = emptyList(), questions = listOf(q(1), q(2)))
        compose.waitForIdle()
        compose.onNodeWithText("Question 1?").assertIsDisplayed()
        compose.onAllNodesWithText("Question 2?").assertCountEquals(0)
        compose.onNodeWithTag("badge-waiting", useUnmergedTree = true).assertTextEquals("1")
    }

    private fun view(talk: List<JSONObject>, questions: List<JSONObject> = emptyList()) = JSONObject().put(
        "dibs",
        JSONObject()
            .put("v", 1).put("now", NOW)
            .put("state", JSONObject().put("brain", "running").put("busy", false).put("limits", JSONObject().put("ts", NOW).put("windows", JSONArray()
                .put(JSONObject().put("name", "five_hour").put("pct", 87.0).put("resets", NOW + 3 * 3600))
                .put(JSONObject().put("name", "seven_day").put("pct", 63.0).put("resets", NOW + 4 * 86400)))))
            .put("talk", JSONArray(talk))
            .put("questions", JSONArray(questions))
            .put("lends", JSONObject()
                .put("phone", JSONObject().put("lent", false).put("text", "").put("action", "phone-lend"))
                .put("laptop", JSONObject().put("lent", true).put("text", "Until 15:40").put("action", "laptop-back"))
                .put("compute", JSONObject().put("lent", false).put("text", "").put("action", "compute-lend").put("busy", 2)))
            .put("yours", JSONArray())
            .put("badges", JSONObject().put("waiting", questions.size)),
    )

    private companion object {
        val NOW = System.currentTimeMillis() / 1000
        const val LONG_TITLE = "Make the dibs app wrap every long title, line and usage window instead of cutting them off with an ellipsis"
        const val LOSES = "Delete throws away 3 saved changes that are not on master yet, and its folder. Stop keeps them."
    }
}
