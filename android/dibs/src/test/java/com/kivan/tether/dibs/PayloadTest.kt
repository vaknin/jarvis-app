package com.kivan.tether.dibs

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PayloadTest {
    // As dibs's src/phoneapp.rs writes it.
    private val view = JSONObject(
        """
        {"v": 1, "badge": 2, "blocks": [], "dibs": {
          "v": 1, "now": 1791213484,
          "state": {"brain": "running", "enabled": true, "busy": true, "line": "dibs is on it", "usage": null},
          "lend": {"until": 1791215000, "holder": "rami-0f", "waiting": 2, "text": "Until 15:40"},
          "talk": [
            {"id": "u-1", "n": 63, "who": "user", "text": "Is it ready?", "ts": 1791212000},
            {"id": "s64", "n": 64, "who": "dibs", "text": "Started task x, as you said (\"long quote\").", "short": "Started task x.", "note": true, "ts": 1791212701},
            {"id": "s65", "n": 65, "who": "dibs", "text": "Inside Tether?", "ts": 1791212800,
             "ask": {"q": 249, "actions": [{"id": "y249", "label": "Yes", "style": "primary"}, {"id": "x249", "label": "Dismiss", "style": "plain"}], "reply": "r249"}},
            {"id": "s66", "n": 66, "who": "dibs", "text": "Separate app?", "ts": 1791212900, "ask": {"q": 250, "outcome": "Answered: Inside Tether"}},
            {"id": "u-2", "n": 67, "who": "user", "text": "look", "ts": 1791213000,
             "files": [{"id": "f1", "name": "a.jpg", "size": 1234, "image": true}, {"id": "f2", "name": "log.txt", "size": 9, "image": false}]}
          ],
          "questions": [{"id": 249, "title": "Inside Tether?", "why": "The plan", "details": "Longer", "from": "dibs", "repo": "tether",
                         "ts": 1791212800, "blocking": false, "kind": "phone", "phone": {"secs": 1800, "unlock": true},
                         "actions": [{"id": "y249", "label": "Lend it", "style": "primary"}], "reply": "r249"}],
          "decided": [{"id": 250, "text": "Shipped", "why": "2 commits", "from": "dibs", "ts": 1791213100, "undo": true, "ack": "k250"}],
          "tasks": [{"id": 16, "name": "build-it", "state": "running", "repo": "tether", "minutes": 112, "text": "Build", "doing": "Writing", "status": "busy", "dir": "~/x"}],
          "sessions": [{"name": "dibs-brain", "repo": "dibs", "branch": "brain-2b", "status": "idle", "task": null, "holds": ["repo:dibs:master", "phone"]}],
          "ships": [{"repo": "dibs", "who": "s", "why": "ship cron", "since": 1791213000, "left": 300}],
          "peek": {"who": "build-it", "at": 1791213400, "lines": ["one", "two"]},
          "recap": {"away": {"id": 4, "title": "While you were away (3h 7m)", "lines": ["Shipped"], "since": 1791200000, "until": 1791211220},
                    "feed": [{"ts": 1791213000, "kind": "did", "who": "a", "text": "Shipped the fix"}]},
          "badges": {"waiting": 2, "work": 1, "recap": 1}
        }}
        """,
    )

    @Test
    fun parsesEveryTab() {
        val d = DibsView.ofView(view)!!
        assertEquals(1791213484, d.now)
        assertTrue(d.state.busy)
        assertNull("a null usage is no usage line", d.state.usage)
        assertEquals("rami-0f", d.lend?.holder)
        assertEquals(5, d.talk.size)
        assertTrue(d.talk[0].mine)
        assertEquals(63, d.talk[0].n)
        assertTrue(d.talk[1].note)
        assertEquals("Started task x.", d.talk[1].short)
        val ask = d.talk[2].ask!!
        assertTrue(ask.open)
        assertEquals("r249", ask.reply)
        assertEquals(listOf("primary", "plain"), ask.actions.map { it.style })
        assertFalse(d.talk[3].ask!!.open)
        assertEquals("Answered: Inside Tether", d.talk[3].ask!!.outcome)
        assertEquals(listOf(FileRef("f1", "a.jpg", 1234, true), FileRef("f2", "log.txt", 9, false)), d.talk[4].files)

        val q = d.questions.single()
        assertEquals("phone", q.kind)
        assertEquals(1800L, q.phoneSecs)
        assertTrue(q.phoneUnlock)
        assertEquals("Longer", q.details)
        assertEquals("tether", q.repo)
        assertEquals("r249", q.reply)

        assertEquals(Decision(250, "Shipped", "2 commits", "dibs", 1791213100, true, "k250"), d.decided.single())
        assertEquals("busy", d.tasks.single().status)
        assertEquals(112, d.tasks.single().minutes)
        assertNull(d.sessions.single().task)
        assertEquals(listOf("repo:dibs:master", "phone"), d.sessions.single().holds)
        assertEquals("dibs", d.ships.single().repo)
        assertEquals(listOf("one", "two"), d.peek?.lines)
        assertEquals(4L, d.away?.id)
        assertEquals("did", d.feed.single().kind)
        assertEquals(Badges(2, 1, 1), d.badges)
    }

    @Test
    fun lendTogglesParse() {
        val d = DibsView.parse(JSONObject("""{"lends": {
          "phone": {"lent": false, "text": "", "until": null, "action": "phone-lend"},
          "laptop": {"lent": true, "text": "Until you take it back · rami-0f is on it", "until": 1791240000, "action": "laptop-back"}}}"""))
        assertEquals(LendToggle(false, "", "phone-lend"), d.lends?.phone)
        assertEquals(LendToggle(true, "Until you take it back · rami-0f is on it", "laptop-back"), d.lends?.laptop)
        assertNull("an older dibs sends no toggles", DibsView.parse(JSONObject("""{"v": 1}""")).lends)
        assertNull("a toggle without an action isn't drawn", DibsView.parse(JSONObject("""{"lends": {"phone": {"lent": true}}}""")).lends?.phone)
    }

    @Test
    fun aViewWithoutThePayloadIsNull() {
        assertNull(DibsView.ofView(JSONObject("""{"v": 1, "blocks": []}""")))
        assertNull(DibsView.ofView(null))
    }

    @Test
    fun missingFieldsStillDraw() {
        val d = DibsView.parse(JSONObject("""{"v": 1}"""))
        assertTrue(d.talk.isEmpty() && d.questions.isEmpty() && d.tasks.isEmpty() && d.feed.isEmpty())
        assertNull(d.lend)
        assertNull(d.away)
        assertNull(d.peek)
        assertFalse(d.state.busy)
        assertEquals(Badges(0, 0, 0), d.badges)
        val q = DibsView.parse(JSONObject("""{"questions": [{"id": 3, "title": "t"}]}""")).questions.single()
        assertEquals("question", q.kind)
        assertNull(q.reply)
        assertNull(q.phoneSecs)
    }
}
