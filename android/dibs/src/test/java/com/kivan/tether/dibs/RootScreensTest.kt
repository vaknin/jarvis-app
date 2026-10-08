package com.kivan.tether.dibs

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.core.view.WindowCompat
import com.github.takahirom.roborazzi.captureRoboImage
import com.kivan.tether.dibs.ui.DibsApp
import com.kivan.tether.dibs.ui.theme.AppTheme
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.Base64

/** The phone's key without Keystore or a fingerprint: what it was asked to sign, and what it answers. */
class FakeKey(var state: KeyState, var result: SignResult = SignResult.Signed(byteArrayOf(0x30, 0x06, 1, 2, 3))) : RootKey {
    val signed = mutableListOf<String>()
    var made = 0

    override suspend fun state(): KeyState = state

    override suspend fun ensure(context: Context): KeyState {
        if (state !is KeyState.Ready) {
            made++
            state = KeyState.Ready(SPKI, strongbox = true)
        }
        return state
    }

    override suspend fun sign(context: Context, message: ByteArray, subtitle: String, description: String): SignResult {
        signed += String(message, Charsets.UTF_8)
        return result
    }

    companion object {
        val SPKI = ByteArray(91) { it.toByte() }
    }
}

/** The root step's card, its page and the key's setup (SPEC.md §3–§5). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w412dp-h915dp-420dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class RootScreensTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private lateinit var host: FakeHost
    private lateinit var key: FakeKey

    @Before
    fun reset() {
        Dibs.pages.clear()
        Dibs.resetTaps()
        Dibs.answered.clear()
        Dibs.fields.clear()
        Dibs.open.clear()
        key = FakeKey(KeyState.Ready(FakeKey.SPKI, strongbox = true))
        Root.key = key
    }

    @After
    fun real() {
        Root.key = KeystoreRootKey
    }

    private fun show(view: JSONObject, tab: String? = "waiting") {
        host = FakeHost(view)
        Dibs.host = host
        Dibs.tab = tab
        WindowCompat.setDecorFitsSystemWindows(compose.activity.window, false)
        compose.setContent { AppTheme { DibsApp() } }
        compose.waitForIdle()
    }

    private fun shot(name: String) = compose.onRoot().captureRoboImage("build/outputs/roborazzi/$name.png")

    private fun scrollTo(text: String) = compose.onNode(hasScrollAction()).performScrollToNode(hasText(text))

    private fun open() {
        compose.onNodeWithText("Review").performClick()
        compose.waitForIdle()
    }

    @Test
    fun theCardShowsTheReasonDibssCheckAndReview() {
        show(view(root()))
        compose.onNodeWithText("ROOT STEP", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Restart Bluetooth").assertIsDisplayed()
        compose.onNodeWithText("In the agent's words").assertIsDisplayed()
        compose.onNodeWithText("“Bluetooth stopped answering after sleep.”").assertIsDisplayed()
        compose.onNodeWithText("dibs would approve it: A plain restart of one service.").assertIsDisplayed()
        compose.onNodeWithText("Review").assertIsDisplayed()
        // No Approve on the card: approving needs the page and the fingerprint.
        compose.onNodeWithText("Approve").assertDoesNotExist()
        shot("root-card")
        compose.onNodeWithText("Deny").performClick()
        assertEquals("d700", host.acts.last().first)
        compose.onNodeWithText("Restart Bluetooth").assertDoesNotExist()
    }

    @Test
    fun aCardDibsHasntCheckedSaysSo() {
        show(view(root(note = null, pick = null)))
        compose.onNodeWithText("dibs hasn't checked this one").assertIsDisplayed()
    }

    @Test
    fun thePageShowsEverythingWordForWordWithThePhonesOwnCode() {
        show(view(root(files = files())))
        open()
        compose.onNodeWithText("ROOT STEP").assertIsDisplayed()
        compose.onNodeWithText("“Bluetooth stopped answering after sleep.”").assertIsDisplayed()
        compose.onNodeWithText("Uses the network.").assertIsDisplayed()
        compose.onNodeWithText("Deletes or overwrites files.").assertIsDisplayed()
        compose.onNodeWithText("Brings app.apk, a file the phone can't show.").assertIsDisplayed()
        shot("root-page-top")
        scrollTo(SCRIPT)
        compose.onNodeWithText(SCRIPT).assertIsDisplayed()
        scrollTo("[Unit]\nDescription=x\n")
        scrollTo("Hash from the laptop, not checked by the phone.")
        compose.onNodeWithText("0".repeat(64)).assertIsDisplayed()
        val r = RootRequest.parse(root(files = files()).getJSONObject("root"))
        val code = RootMessage.shortHash(RootMessage.build(r, false))
        scrollTo(code)
        compose.onNodeWithText(code).assertIsDisplayed()
        scrollTo("Never ask again")
        compose.onNodeWithText("Runs this exact script again without asking").assertIsDisplayed()
        shot("root-page-bottom")
        // Ticked, the message says so: its code changes.
        compose.onNodeWithText("Never ask again").performClick()
        compose.onNodeWithText(RootMessage.shortHash(RootMessage.build(r, true))).assertExists()
    }

    @Test
    fun approveSignsTheMessageBuiltHereAndSendsIt() {
        show(view(root()))
        open()
        scrollTo("Approve")
        compose.onNode(hasText("Never ask again")).assertExists()
        compose.onNodeWithText("Never ask again").performClick()
        compose.onNodeWithText("Approve").assertIsEnabled().performClick()
        compose.waitForIdle()
        val r = RootRequest.parse(root().getJSONObject("root"))
        assertEquals(listOf(RootMessage.build(r, remember = true)), key.signed)
        val (action, value) = host.acts.last()
        assertEquals("root-approve", action)
        assertEquals(700L, value!!.getLong("item"))
        assertEquals(12L, value.getLong("request"))
        assertEquals(Base64.getEncoder().encodeToString(byteArrayOf(0x30, 0x06, 1, 2, 3)), value.getString("sig"))
        assertEquals(true, value.getBoolean("remember"))
        // Back on Waiting, the card is answered.
        assertTrue(Dibs.pages.isEmpty())
        compose.onNodeWithText("Restart Bluetooth").assertDoesNotExist()
    }

    @Test
    fun neverAskAgainIsOffByDefault() {
        show(view(root()))
        open()
        scrollTo("Approve")
        compose.onNode(hasText("Never ask again")).assertExists()
        compose.onNodeWithText("Approve").performClick()
        compose.waitForIdle()
        assertTrue(key.signed.single().contains("remember no\n"))
        assertEquals(false, host.acts.last().second!!.getBoolean("remember"))
    }

    @Test
    fun aClosedPromptSendsNothing() {
        key.result = SignResult.Cancelled
        show(view(root()))
        open()
        scrollTo("Approve")
        compose.onNodeWithText("Approve").performClick()
        compose.waitForIdle()
        assertTrue(host.acts.none { it.first == "root-approve" })
        compose.onNodeWithText("Approve").assertIsEnabled()
    }

    @Test
    fun anExpiredRequestCantBeApproved() {
        show(view(root(expires = NOW - 10)))
        open()
        scrollTo("Approve")
        compose.onNodeWithText("This request expired", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Approve").assertIsNotEnabled()
        compose.onNodeWithText("Deny").assertIsEnabled()
    }

    @Test
    fun anInvalidatedKeySaysToSetUpAgain() {
        key.state = KeyState.Invalidated
        show(view(root()))
        open()
        scrollTo("Approve")
        compose.onNodeWithText(Root.INVALIDATED).assertIsDisplayed()
        compose.onNodeWithText("Approve").assertIsNotEnabled()
        shot("root-page-invalidated")
        compose.onNodeWithText("Set up again").performClick()
        compose.waitForIdle()
        assertEquals(1, key.made)
        assertEquals("root-key", host.acts.last().first)
    }

    @Test
    fun denyOnThePageSendsTheCardsDeny() {
        show(view(root()))
        open()
        scrollTo("Deny")
        compose.onNodeWithText("Deny").performClick()
        compose.waitForIdle()
        assertEquals("d700", host.acts.last().first)
        assertTrue(Dibs.pages.isEmpty())
    }

    @Test
    fun setUpMakesTheKeyShowsItsCodeAndSendsIt() {
        key.state = KeyState.None
        show(view(rootKey()))
        compose.onNodeWithText("Set up root steps").assertIsDisplayed()
        compose.onNodeWithText("Set up").performClick()
        compose.waitForIdle()
        val fp = RootMessage.fingerprint(FakeKey.SPKI)
        compose.onNodeWithText(fp).assertIsDisplayed()
        compose.onNodeWithText("Kept in the phone's security chip.").assertIsDisplayed()
        shot("root-key")
        val (action, value) = host.acts.single()
        assertEquals("root-key", action)
        assertEquals(Base64.getEncoder().encodeToString(FakeKey.SPKI), value!!.getString("spki"))
        assertEquals(true, value.getBoolean("strongbox"))
        assertEquals(fp, value.getString("fingerprint"))
    }

    @Test
    fun aRootQuestionInTheChatOffersReviewNotApprove() {
        val q = root()
        val v = view(q)
        v.getJSONObject("dibs").put(
            "talk",
            JSONArray().put(
                JSONObject().put("id", "s1").put("n", 1).put("who", "dibs").put("text", "A root step waits: Restart Bluetooth").put("ts", NOW - 60)
                    .put("ask", JSONObject().put("q", 700).put("actions", q.getJSONArray("actions"))),
            ),
        )
        show(v, tab = "chat")
        compose.onNodeWithText("Review").assertIsDisplayed()
        compose.onNodeWithText("Review").performClick()
        compose.waitForIdle()
        assertEquals(Page.Root(700), Dibs.pages.last())
    }

    @Test
    @Config(qualifiers = "w320dp-h640dp-420dpi")
    fun thePageFitsASmallScreen() {
        show(view(root(files = files())))
        open()
        shot("root-page-small")
        scrollTo("Approve")
        compose.onNodeWithText("Approve").assertIsDisplayed()
        compose.onNodeWithText("Deny").assertIsDisplayed()
        shot("root-page-small-bottom")
    }

    @Test
    fun theKeysOwnStatesOnThePage() {
        key.state = KeyState.None
        show(view(root()))
        open()
        scrollTo("Approve")
        compose.onNodeWithText("This phone has no key for root steps yet", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Approve").assertIsNotEnabled()
        compose.onNodeWithText("Never ask again").performClick()
        compose.onNode(hasText("Never ask again")).assertExists()
    }

    private fun files() = JSONArray()
        .put(JSONObject().put("name", "x.service").put("text", "[Unit]\nDescription=x\n"))
        .put(JSONObject().put("name", "app.apk").put("size", 31457280).put("sha256", "0".repeat(64)))

    private fun root(note: String? = "A plain restart of one service.", pick: String? = "accept", expires: Long = NOW + 11 * 3600, files: JSONArray = JSONArray()) =
        JSONObject().put("id", 700).put("title", "Restart Bluetooth").put("why", "Bluetooth stopped answering after sleep.")
            .put("from", "fix-bt").put("ts", NOW - 120).put("kind", "root")
            .put("actions", JSONArray().put(JSONObject().put("id", "d700").put("label", "Deny").put("style", "plain")))
            .put(
                "root",
                JSONObject().put("request", 12).put("machine", "0123456789abcdef0123456789abcdef").put("nonce", "00112233445566778899aabbccddeeff")
                    .put("expires", expires).put("network", files.length() > 0).put("home", "no").put("timeout", 600)
                    .put("script", SCRIPT).put("files", files)
                    .put("why", "Bluetooth stopped answering after sleep.")
                    .put("note", note ?: JSONObject.NULL).put("pick", pick ?: JSONObject.NULL)
                    .put("allow_list", JSONArray()),
            )

    private fun rootKey() = JSONObject().put("id", 701).put("title", "Set up root steps").put("why", "So you can approve root steps from the phone.")
        .put("from", "dibs").put("ts", NOW - 60).put("kind", "rootkey")
        .put("actions", JSONArray().put(JSONObject().put("id", "y701").put("label", "Set up").put("style", "primary")))

    private fun view(vararg questions: JSONObject) = JSONObject().put(
        "dibs",
        JSONObject().put("v", 1).put("now", NOW)
            .put("state", JSONObject().put("brain", "running").put("busy", false))
            .put("talk", JSONArray())
            .put("questions", JSONArray(questions.toList()))
            .put("yours", JSONArray())
            .put("badges", JSONObject().put("waiting", questions.size)),
    )

    private companion object {
        val NOW = System.currentTimeMillis() / 1000
        const val SCRIPT = "#!/bin/bash\nset -e\nrm -f /var/lib/bluetooth/cache\nsystemctl restart bluetooth.service\n"
    }
}
