package com.kivan.tether.dibs

import org.json.JSONArray
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
          "state": {"brain": "running", "enabled": true, "busy": true, "line": "dibs is on it", "usage": null, "doing": "working", "words": "Working"},
          "lend": {"until": 1791215000, "holder": "rami-0f", "waiting": 2, "text": "Until 15:40"},
          "talk": [
            {"id": "u-1", "n": 63, "who": "user", "text": "Is it ready?", "ts": 1791212000},
            {"id": "s64", "n": 64, "who": "dibs", "text": "Started task x, as you said (\"long quote\").", "short": "Started task x.", "note": true, "ts": 1791212701},
            {"id": "s65", "n": 65, "who": "dibs", "text": "Inside Tether?", "ts": 1791212800,
             "ask": {"q": 249, "actions": [{"id": "y249", "label": "Yes", "style": "primary"}, {"id": "x249", "label": "Drop this question", "style": "plain"}], "reply": "r249", "hint": "Answer in your own words…",
                     "read": {"label": "Read it in full", "task": 42, "url": null}}},
            {"id": "s66", "n": 66, "who": "dibs", "text": "Separate app?", "ts": 1791212900, "ask": {"q": 250, "outcome": "Answered: Inside Tether"}},
            {"id": "u-2", "n": 67, "who": "user", "text": "look", "ts": 1791213000,
             "files": [{"id": "f1", "name": "a.jpg", "size": 1234, "image": true}, {"id": "f2", "name": "log.txt", "size": 9, "image": false}]}
          ],
          "questions": [{"id": 249, "title": "Inside Tether?", "why": "The plan", "details": "Longer", "from": "dibs", "repo": "tether",
                         "ts": 1791212800, "blocking": false, "kind": "phone", "phone": {"secs": 1800, "unlock": true},
                         "actions": [{"id": "y249", "label": "Lend it", "style": "primary"}], "reply": "r249", "hint": "Add a comment…",
                         "read": {"task": 42, "url": "https://example.com/plan"}}],
          "decided": [{"id": 250, "text": "Shipped", "why": "2 commits", "from": "dibs", "ts": 1791213100, "undo": true, "ack": "k250"}],
          "tasks": [{"id": 16, "name": "build-it", "state": "running", "repo": "tether", "minutes": 112, "text": "Build", "doing": "Writing", "status": "busy", "dir": "~/x"}],
          "sessions": [{"name": "dibs-brain", "repo": "dibs", "branch": "brain-2b", "status": "idle", "task": null, "holds": ["repo:dibs:master", "phone"]}],
          "ships": [{"repo": "dibs", "who": "s", "why": "ship cron", "since": 1791213000, "left": 300}],
          "peek": {"who": "build-it", "at": 1791213400, "lines": ["one", "two"]},
          "recap": {"away": {"id": 4, "title": "While you were away (3h 7m)", "lines": ["Shipped"], "since": 1791200000, "until": 1791211220},
                    "feed": [{"ts": 1791213000, "kind": "did", "who": "a", "text": "Shipped the fix"}],
                    "decided": [{"id": 251, "text": "Your app is blue now.", "why": "It matches the icon.", "from": "a", "ts": 1791213200, "undo": true, "ack": "k251", "project": "Rami", "plain": true, "raw": "Picked blue"},
                                {"id": 250, "text": "Shipped", "why": "2 commits", "from": "dibs", "ts": 1791213100, "undo": true, "ack": "k250"}]},
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
        assertEquals("working", d.state.doing)
        assertEquals("Working", d.state.words)
        assertEquals("rami-0f", d.lend?.holder)
        assertEquals(5, d.talk.size)
        assertTrue(d.talk[0].mine)
        assertEquals(63, d.talk[0].n)
        assertTrue(d.talk[1].note)
        assertEquals("Started task x.", d.talk[1].short)
        val ask = d.talk[2].ask!!
        assertTrue(ask.open)
        assertEquals("r249", ask.reply)
        assertEquals("Answer in your own words…", ask.hint)
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
        assertEquals("Add a comment…", q.hint)

        assertEquals(Decision(250, "Shipped", "2 commits", "dibs", 1791213100, true, "k250"), d.decided.single())
        assertEquals("Recap's own list, read ones too", listOf(251L, 250L), d.recapDecided.map { it.id })
        assertEquals(Triple("Rami", true, "Picked blue"), d.recapDecided[0].let { Triple(it.project, it.plain, it.raw) })
        assertEquals("not written yet: the agent's words", Triple("", false, ""), d.recapDecided[1].let { Triple(it.project, it.plain, it.raw) })
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
    fun aFeedEntryCarriesItsWholeStory() {
        val d = DibsView.parse(
            JSONObject(
                """{"recap": {"feed": [
                  {"id": "t25", "ts": 5, "kind": "done", "who": "add-the-brain-s", "repo": "dibs", "text": "dibs's brain has its personality.",
                   "why": "Add the brain's personality.", "asked": "add the brain's personality (artifact x). Keep it short.",
                   "more": ["dibs talks calm and short."], "report": "Added it.\nShipped.", "reopen": "sid-1"},
                  {"ts": 4, "kind": "update", "who": "dibs", "text": "Installed an update to dibs", "report": "#16 x installed"},
                  {"ts": 3, "kind": "did", "who": "a", "text": "Shipped the fix"}]}}""",
            ),
        )
        val (task, update, bare) = d.feed
        assertEquals("dibs", task.repo)
        assertEquals("feed:t25", task.key)
        assertEquals("sid-1", task.reopen)
        assertNull("no saved chat, no button", update.reopen)
        assertEquals("an older dibs sends no id", "feed:3:a", bare.key)
        assertEquals(listOf("dibs talks calm and short."), task.more)
        assertTrue(task.opens)
        assertEquals(
            "dibs's brain has its personality.\n\nReport:\nAdded it.\nShipped.\n\nAlong the way:\n• dibs talks calm and short." +
                "\n\nYou asked: add the brain's personality (artifact x). Keep it short.",
            task.full(),
        )
        assertTrue("an update's technical line is behind the tap", update.opens)
        assertFalse("an older dibs's bare line has nothing behind it", bare.opens)
        assertNull(bare.why)
    }

    @Test
    fun readItInFullComesOnAnAskAndACardAndIsOptional() {
        val d = DibsView.ofView(view)!!
        assertEquals(ReadInFull("Read it in full", 42, null), d.talk[2].ask!!.read)
        assertNull("a closed ask has none", d.talk[3].ask!!.read)
        assertEquals("the label has a default", ReadInFull("Read it in full", 42, "https://example.com/plan"), d.questions.single().read)
        fun read(r: String) = DibsView.parse(JSONObject("""{"questions": [{"id": 1, "title": "x", "read": $r}]}""")).questions.single().read
        assertNull("only a secure link opens", read("""{"task": 7, "url": "http://example.com"}""")!!.url)
        assertNull("no task, nothing to read", read("""{"url": "https://example.com"}"""))
        assertNull(read("\"yes\""))
    }

    @Test
    fun lendTogglesParse() {
        val d = DibsView.parse(JSONObject("""{"lends": {
          "phone": {"lent": false, "text": "", "until": null, "action": "phone-lend"},
          "laptop": {"lent": true, "text": "Until you take it back · rami-0f is on it", "until": 1791240000, "action": "laptop-back"}}}"""))
        assertEquals(LendToggle(false, "", "phone-lend"), d.lends?.phone)
        assertEquals(LendToggle(true, "Until you take it back · rami-0f is on it", "laptop-back"), d.lends?.laptop)
        assertNull("an older dibs sends no compute card", d.lends?.compute)
        val c = DibsView.parse(JSONObject("""{"lends": {"compute": {"lent": true, "text": "2 tasks running there", "action": "compute-back", "busy": 2}}}""")).lends?.compute
        assertEquals(LendToggle(true, "2 tasks running there", "compute-back", 2), c)
        assertEquals("busy defaults to none", 0, DibsView.parse(JSONObject("""{"lends": {"compute": {"lent": false, "action": "compute-lend"}}}""")).lends?.compute?.busy)
        assertNull("an older dibs sends no toggles", DibsView.parse(JSONObject("""{"v": 1}""")).lends)
        assertNull("a toggle without an action isn't drawn", DibsView.parse(JSONObject("""{"lends": {"phone": {"lent": true}}}""")).lends?.phone)
    }

    @Test
    fun looksParse() {
        val d = DibsView.parse(
            JSONObject(
                """{"badges": {"waiting": 0, "work": 0, "recap": 0, "looks": 2},
                  "looks": [
                    {"id": 7, "kind": "plan", "title": "Terminal choice", "pick": "tmux: it keeps sessions.", "link": "https://example.com/p",
                     "task": 210, "follow": 211, "follow_state": "queued", "ts": 5, "read": false},
                    {"id": 8, "kind": "research", "title": "Found", "pick": "It is X.", "link": "http://example.com/p",
                     "task": 212, "follow": null, "follow_state": null, "ts": 4, "read": true}]}""",
            ),
        )
        assertEquals(2, d.badges.looks)
        val (a, b) = d.looks
        assertEquals(Look(7, "plan", "Terminal choice", "tmux: it keeps sessions.", "https://example.com/p", 210, 211, "queued", 5, false), a)
        assertNull("a link that isn't https is dropped", b.link)
        assertNull(b.follow)
        assertNull(b.followState)
        assertTrue(b.read)
    }

    @Test
    fun noLooksKeyIsNoLooks() {
        val d = DibsView.parse(JSONObject("""{"v": 1, "badges": {"waiting": 1}}"""))
        assertTrue(d.looks.isEmpty())
        assertEquals(0, d.badges.looks)
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
        assertNull(q.hint)
        assertNull(q.phoneSecs)
        val old = DibsView.parse(JSONObject("""{"decided": [{"id": 9, "text": "x", "undo": false, "ack": "k9"}], "recap": {"feed": []}}"""))
        assertEquals("an older dibs: Recap shows the unread ones", listOf(9L), old.recapDecided.map { it.id })
        assertNull("an older dibs sends no yours: the old Work tab", d.yours)
        assertNull(q.task)
    }

    @Test
    fun aTaskLineCanCarryItsQuestion() {
        val d = DibsView.parse(
            JSONObject(
                """{"now": 1, "yours": [{"id": 5, "state": "working", "talk": [
                  {"id": "t5-1", "who": "agent", "text": "plain", "ts": 1},
                  {"id": "t5-2", "who": "agent", "text": "Root step", "ts": 2, "ask": {"q": 77, "actions": [{"id": "d77", "label": "Deny", "style": "danger"}]}}]}]}""",
            ),
        )
        val talk = d.yours!!.single().talk
        assertNull(talk[0].ask)
        assertEquals(77L, talk[1].ask!!.q)
        assertEquals("Deny", talk[1].ask!!.actions.single().label)
    }

    // As dibs's src/yours.rs writes it (app 0.5.0 and newer).
    @Test
    fun yourTasks() {
        val d = DibsView.parse(
            JSONObject(
                """{"badges": {"waiting": 1, "work": 0, "recap": 0, "tasks": 2},
                  "questions": [{"id": 249, "title": "Which one?", "task": 31}],
                  "recap": {"feed": [{"id": "t31", "ts": 9, "kind": "done", "who": "recap", "text": "Recap folds", "task": 31}]},
                  "tasks": [{"id": 40, "name": "tidy", "state": "running", "background": true}],
                  "yours": [
                    {"id": 31, "title": "Recap: one entry per finished job", "name": "the-dibs-app-s-recap-tab", "project": "tether",
                     "state": "done", "ts": 1791230000, "started": 1791224300, "finished": 1791230000, "minutes": 95,
                     "line": "Recap shows one entry per job.", "asked": "make recap tidy", "report": "Did it.",
                     "result": {"report_md": true, "shipped": [{"repo": "tether", "changes": 3, "for_you": ["Recap is tidy."]}]},
                     "questions": [249], "busy": false, "live": true, "unread": true,
                     "talk": [{"id": "t31-4", "who": "agent", "text": "Done, see the report.", "ts": 1791230000},
                              {"id": "u-9", "who": "user", "text": "why?", "short": "why", "ts": 1791230100,
                               "files": [{"id": "f1", "name": "a.jpg", "size": 3, "image": true}]},
                              {"id": "t31-6", "who": "note", "text": "Reopened its chat on the laptop", "ts": 1791230200}]},
                    {"id": 32, "title": "", "name": "mini-pc", "state": "working", "ts": 5, "started": 4, "ticked": 1791230300}
                  ]}""",
            ),
        )
        assertEquals(Badges(1, 0, 0, 2), d.badges)
        assertEquals(31L, d.questions.single().task)
        assertEquals(31L, d.feed.single().task)
        assertFalse("a task's row opens its page instead", d.feed.single().opens)
        assertTrue(d.tasks.single().background)
        val (t, u) = d.yours!!
        assertEquals("tether", t.project)
        assertEquals(1791230000L, t.finished)
        assertEquals(95L, t.minutes)
        assertEquals(TaskResult(true, listOf(Shipped("tether", 3, listOf("Recap is tidy.")))), t.result)
        assertEquals(listOf(249L), t.questions)
        assertTrue(t.unread && t.live && !t.busy && t.finishedState)
        assertNull(t.ticked)
        val (agent, mine, note) = t.talk
        assertFalse(agent.mine || agent.note)
        assertTrue(mine.mine)
        assertEquals("u-9", mine.id)
        assertEquals("why", mine.short)
        assertEquals("a.jpg", mine.files.single().name)
        assertTrue(note.note)
        assertEquals("Other", u.project)
        assertEquals("mini-pc", u.label)
        assertNull(u.finished)
        assertNull(u.minutes)
        assertNull(u.result)
        assertEquals(1791230300L, u.ticked)
        assertEquals(u, d.task(32))
    }

    @Test
    fun readsUsageAndTheLaptop() {
        val d = DibsView.ofView(JSONObject("""{"dibs": {"state": {"busy": false,
            "limits": {"ts": 5, "windows": [{"name": "five_hour", "pct": 87.5, "resets": 100}]},
            "laptop": {"mem_used": 1024, "mem_total": 2048, "load": 1.5, "cores": 16, "builds": 2, "waiting": null, "agents": 3, "name": "Server"}}}}"""))!!
        assertEquals(listOf(Limit("five_hour", 87.5, 100)), d.state.limits)
        assertEquals(Laptop(1024, 2048, 1.5, 16, 2, null, 3, "Server"), d.state.laptop)
        val old = DibsView.ofView(JSONObject("""{"dibs": {"state": {"busy": true}}}"""))!!
        assertEquals(emptyList<Limit>(), old.state.limits)
        assertNull(old.state.laptop)
    }

    @Test
    fun readsWhetherTheBrainIsOnButDown() {
        val down = DibsView.ofView(JSONObject("""{"dibs": {"state": {"brain": null, "enabled": true, "busy": false, "doing": "down", "words": "Not running"}}}"""))!!
        assertEquals(true, down.state.enabled)
        assertEquals(true, down.state.brainDown)
        assertEquals("Not running", stateWords(Link.CONNECTED, down.state))
        val up = DibsView.ofView(JSONObject("""{"dibs": {"state": {"brain": "running", "enabled": true}}}"""))!!
        assertEquals(false, up.state.brainDown)
        val starting = DibsView.ofView(JSONObject("""{"dibs": {"state": {"brain": "starting", "enabled": true}}}"""))!!
        assertEquals(false, starting.state.brainDown)
        val off = DibsView.ofView(JSONObject("""{"dibs": {"state": {"brain": null, "enabled": false}}}"""))!!
        assertEquals(false, off.state.brainDown)
        // An older dibs sends no "enabled": no button.
        val old = DibsView.ofView(JSONObject("""{"dibs": {"state": {"busy": true}}}"""))!!
        assertEquals(false, old.state.enabled)
        assertEquals(false, old.state.brainDown)
    }

    // As dibs writes a task's full story (task #85): absent until asked, and its "ready" note in the chat.
    @Test
    fun fullStories() {
        val d = DibsView.parse(
            JSONObject(
                """{"talk": [{"id": "s70", "n": 70, "who": "dibs", "text": "The full story of “Recap” is ready.", "note": true, "ts": 9,
                              "open": {"story": 31}},
                             {"id": "s71", "n": 71, "who": "dibs", "text": "Hi", "ts": 10, "open": {}}],
                  "yours": [
                    {"id": 31, "title": "Recap", "state": "done",
                     "story": {"state": "ready", "ts": 1791230000, "stale": true, "have": true, "by": "writer"}},
                    {"id": 32, "title": "Mini PC", "state": "working",
                     "story": {"state": "writing", "since": 1791230100, "by": "agent"}},
                    {"id": 33, "title": "Never asked", "state": "done"}
                  ]}""",
            ),
        )
        assertEquals(31L, d.talk[0].open)
        assertNull("an open without a story opens nothing", d.talk[1].open)
        val (ready, writing, never) = d.yours!!
        assertEquals(Story("ready", ts = 1791230000, stale = true, have = true, by = "writer"), ready.story)
        assertFalse(ready.story!!.writing)
        assertEquals(Story("writing", since = 1791230100, by = "agent"), writing.story)
        assertTrue(writing.story!!.writing)
        assertNull(never.story)
    }

    @Test
    fun parsesTheBoardsRoomWhenDibsSendsIt() {
        fun board(room: String) = DibsView.parse(JSONObject("""{"now": 1, "yours": [], "board": {"columns": [], "done": {"count": 0, "cards": []}$room}}""")).board!!
        assertEquals(BoardRoom(0, "No room for another task: 14 of 15.6 GB used"), board(""", "room": {"room": 0, "why": "memory", "line": "No room for another task: 14 of 15.6 GB used"}""").room)
        assertEquals(BoardRoom(2, "Room for 2 more"), board(""", "room": {"room": 2, "line": "Room for 2 more"}""").room)
        assertNull(board("").room)
        // The phone view has no board room; its queue block's count and reason stand in.
        val q = DibsView.parse(JSONObject("""{"now": 1, "yours": [], "board": {"columns": [], "done": {"count": 0, "cards": []}}, "queue": {"room": 0, "why": "2.0 GB free"}}""")).board!!
        assertEquals(BoardRoom(0, "No room for another task now: 2.0 GB free"), q.room)
    }

    @Test
    fun parsesTheQueuesHoldAndDropsItWhenItLifts() {
        fun board(hold: String) = DibsView.parse(JSONObject("""{"now": 1, "yours": [], "board": {"columns": [], "done": {"count": 0, "cards": []}$hold}}""")).board!!
        val line = "On hold while dibs moves to the server: nothing new starts"
        assertEquals(line, board(""", "hold": {"why": "move", "line": "$line"}""").hold)
        assertNull(board("").hold)
        assertNull(board(""", "hold": {"why": "move", "line": ""}""").hold)
    }

    @Test
    fun parsesTheLineAReplyAnswers() {
        val d = DibsView.parse(
            JSONObject(
                """
                {"now": 1791213484, "talk": [
                  {"id": "s1", "n": 1, "who": "dibs", "text": "Started task x", "ts": 1},
                  {"id": "u-2", "n": 2, "who": "user", "text": "ok", "ts": 2, "reply": {"n": 1, "who": "dibs", "text": "Started task x, as you said"}}]}
                """,
            ),
        )
        assertNull(d.talk[0].reply)
        assertEquals(ReplyRef(1, "dibs", "Started task x, as you said"), d.talk[1].reply)
    }

    @Test
    fun parsesTheBoardAsSent() {
        val d = DibsView.parse(
            JSONObject(
                """
                {"now": 1791213484, "yours": [],
                 "board": {
                   "columns": [
                     {"key": "now", "title": "Working now", "cards": [
                       {"key": "task:31", "n": 31, "title": "Recap: one entry per finished job", "state_words": "working",
                        "now": "Running the tests", "tags": ["work saved"], "story": {"state": "ready", "ts": 1791213000, "have": true},
                        "actions": ["hold", "stop", "story"],
                        "progress": {"stage": "build", "words": "building, step 2 of 5", "from": 1791210000, "eta": 1791214400,
                                     "lo": 1791214000, "hi": 1791215400, "guess": false, "seen": 12}}]},
                     {"key": "next", "title": "Queue", "cards": [
                       {"key": "idea:7", "n": null, "title": "A widget for the board", "state_words": "an idea", "now": "",
                        "tags": [], "story": null, "actions": ["up", "down", "to_later", "delete"]}]},
                     {"key": "later", "title": "Later", "cards": []}
                   ],
                   "done": {"count": 12, "cards": [{"key": "task:29", "n": 29, "title": "Lend toggles", "state_words": "done"}]}
                 }}
                """,
            ),
        )
        val b = d.board!!
        assertEquals(listOf("now", "next", "later"), b.columns.map { it.key })
        assertEquals("Working now", b.columns[0].title)
        val c = b.columns[0].cards.single()
        assertEquals("task:31", c.key)
        assertEquals(31L, c.n)
        assertEquals(31L, c.task)
        assertEquals("working", c.stateWords)
        assertEquals("Running the tests", c.now)
        assertEquals(listOf("work saved"), c.tags)
        assertEquals("ready", c.story?.state)
        assertTrue(c.story!!.have)
        assertEquals(listOf("hold", "stop", "story"), c.actions)
        // An old dibs sends no primary or lane.
        assertNull(c.primary)
        assertNull(c.lane)
        assertEquals(Progress("build", "building, step 2 of 5", 1791210000, 1791214400, 1791214000, 1791215400, false), c.progress)
        val idea = b.columns[1].cards.single()
        assertNull("an idea has no number", idea.n)
        assertNull("nor a task page", idea.task)
        assertNull(idea.story)
        assertNull("only a running task has progress", idea.progress)
        assertEquals("", idea.now)
        assertTrue(b.columns[2].cards.isEmpty())
        assertEquals(12, b.doneCount)
        assertEquals("Lend toggles", b.done.single().title)
        assertEquals(emptyList<String>(), b.done.single().actions)
    }

    /** The two-list board (active, backlog), each card's `primary` and an Active card's `lane`, reads as sent. */
    @Test
    fun parsesTheActiveAndBacklogBoard() {
        val d = DibsView.parse(
            JSONObject(
                """
                {"now": 1791213484, "yours": [],
                 "board": {
                   "columns": [
                     {"key": "active", "title": "Active", "counts": {"working": 1}, "cards": [
                       {"key": "task:1", "n": 1, "title": "One", "state_words": "working", "actions": ["park", "down", "stop", "story"],
                        "primary": "park", "lane": "running"},
                       {"key": "task:3", "n": 3, "title": "Three", "state_words": "next", "actions": ["park", "start_now", "delete"],
                        "primary": "park", "lane": "waiting"}]},
                     {"key": "backlog", "title": "Backlog", "cards": [
                       {"key": "idea:note-41", "n": null, "title": "A note", "state_words": "your note", "actions": ["start", "down"],
                        "primary": "start", "move": "x"}]}
                   ],
                   "done": {"count": 1, "cards": [{"key": "task:7", "n": 7, "title": "Done one", "state_words": "done", "actions": ["story"]}]}
                 }}
                """,
            ),
        )
        val b = d.board!!
        assertEquals(listOf("active", "backlog"), b.columns.map { it.key })
        assertEquals(listOf("Active", "Backlog"), b.columns.map { it.title })
        assertEquals(listOf("park", "park"), b.columns[0].cards.map { it.primary })
        assertEquals(listOf("running", "waiting"), b.columns[0].cards.map { it.lane })
        val note = b.columns[1].cards.single()
        assertEquals("start", note.primary)
        assertNull(note.lane)
        assertEquals(listOf("start", "down"), note.actions)
        // Done cards carry no primary.
        assertNull(b.done.single().primary)
        assertNull(b.done.single().lane)
    }

    /** dibs's own recorded board (its tests/samples/board.json, copied here) reads as sent. */
    @Test
    fun parsesDibssSampleBoard() {
        val sample = JSONObject(javaClass.getResource("/board.json")!!.readText())
        val b = DibsView.parse(JSONObject().put("now", 1791213484).put("yours", JSONArray()).put("board", sample.getJSONObject("board"))).board!!
        assertEquals(listOf("active", "backlog"), b.columns.map { it.key })
        assertEquals(listOf("task:1", "task:2", "task:3", "task:4"), b.columns[0].cards.map { it.key })
        assertEquals(listOf("running", "running", "waiting", "waiting"), b.columns[0].cards.map { it.lane })
        assertTrue(b.columns[0].cards.all { it.primary == "park" })
        assertEquals(listOf("drop:bbbbbbbbbbbbbbbb0000000000000002", "drop:bbbbbbbbbbbbbbbb0000000000000001", "idea:note-41", "task:5", "task:6"), b.columns[1].cards.map { it.key })
        assertEquals("Add to the Backlog: an idea, a problem, a task", b.columns[1].box)
        // A drop dibs reads or asks about: no number, no button, a tap opens its idea.
        val drop = b.columns[1].cards[0]
        assertNull(drop.n)
        assertNull(drop.primary)
        assertTrue(drop.actions.isEmpty())
        assertEquals("dibs asks: Which app: the phone or the laptop?", drop.stateWords)
        assertTrue(drop.open!!.startsWith("idea:"))
        assertTrue(b.columns[1].cards.filter { !it.key.startsWith("drop:") }.all { it.primary == "start" && it.lane == null })
        val first = b.columns[0].cards.first()
        assertEquals("", first.now)
        assertNull(first.story)
        assertNull(first.deleteText)
        assertEquals(listOf("park", "down", "stop", "story"), first.actions)
        val saved = b.columns[1].cards.single { it.key == "task:5" }
        assertTrue(saved.deleteText!!.startsWith("Delete throws away 3 saved changes"))
        assertNull(b.columns[1].cards.single { it.key == "idea:note-41" }.n)
        assertEquals(1, b.doneCount)
        assertEquals(listOf("story"), b.done.single().actions)
        assertNull(b.done.single().primary)
    }

    /** A JSON null never reads as the word "null", and keyless or repeated cards and columns are dropped. */
    @Test
    fun aBoardWithNullsAndRepeatsStaysDrawable() {
        val d = DibsView.parse(
            JSONObject(
                """
                {"now": 1791213484, "yours": [],
                 "board": {
                   "columns": [
                     {"key": "now", "title": null, "cards": [
                       {"key": "task:1", "n": 1, "title": null, "state_words": null, "now": null, "tags": ["work saved", null], "actions": [null, "stop"]},
                       {"key": "task:1", "n": 1, "title": "again"},
                       {"key": null, "title": "no key"},
                       {"title": "no key either"}]},
                     {"key": "now", "title": "again", "cards": []},
                     {"key": null, "title": "no key", "cards": []}
                   ],
                   "done": {"count": null}
                 }}
                """,
            ),
        )
        val b = d.board!!
        assertEquals(listOf("now"), b.columns.map { it.key })
        assertEquals("", b.columns[0].title)
        val c = b.columns[0].cards.single()
        assertEquals("", c.title)
        assertEquals("", c.stateWords)
        assertEquals("", c.now)
        assertEquals(listOf("work saved"), c.tags)
        assertEquals(listOf("stop"), c.actions)
        assertEquals(0, b.doneCount)
    }

    @Test
    fun anOlderDibsSendsNoBoard() {
        assertNull(DibsView.ofView(view)!!.board)
    }

    @Test
    fun aRootStepCarriesItsRequest() {
        val d = DibsView.parse(
            JSONObject(
                """
                {"questions": [
                  {"id": 700, "title": "Restart Bluetooth", "why": "w", "from": "fix-bt", "ts": 1, "kind": "root",
                   "actions": [{"id": "d700", "label": "Deny", "style": "plain"}],
                   "root": {"request": 12, "machine": "0123456789abcdef0123456789abcdef", "nonce": "00112233445566778899aabbccddeeff",
                            "expires": 1760000000, "network": true, "home": "ro", "timeout": 900,
                            "script": "#!/bin/bash\nsystemctl restart bluetooth.service\n",
                            "files": [{"name": "x.service", "text": ""}, {"name": "app.apk", "size": 31457280, "sha256": "ab"}],
                            "why": "Bluetooth is stuck", "note": "Fine, a restart.", "pick": "accept",
                            "allow_list": [{"name": "Restart Bluetooth", "action": "cd"}]}},
                  {"id": 701, "title": "Not yet checked", "kind": "root",
                   "root": {"request": 13, "script": "true\n", "note": null, "pick": null}},
                  {"id": 702, "title": "Set up root steps", "why": "Once", "kind": "rootkey", "actions": [{"id": "y702", "label": "Set up"}]}
                ]}
                """,
            ),
        )
        val q = d.questions[0]
        assertEquals("root", q.kind)
        val r = q.root!!
        assertEquals(12L, r.request)
        assertEquals("0123456789abcdef0123456789abcdef", r.machine)
        assertEquals(1760000000L, r.expires)
        assertTrue(r.network)
        assertEquals("ro", r.home)
        assertEquals(900L, r.timeout)
        assertEquals("#!/bin/bash\nsystemctl restart bluetooth.service\n", r.script)
        assertEquals("an empty text file is still text", "", r.files[0].text)
        assertFalse(r.files[0].binary)
        assertTrue(r.files[1].binary)
        assertEquals(31457280L, r.files[1].size)
        assertEquals("ab", r.files[1].sha256)
        assertEquals("Bluetooth is stuck", r.why)
        assertEquals("Fine, a restart.", r.note)
        assertEquals("accept", r.pick)
        assertEquals(listOf(RootAllowed("Restart Bluetooth", "cd")), r.allowList)

        val unchecked = d.questions[1].root!!
        assertNull("a null note: dibs hasn't checked it", unchecked.note)
        assertNull(unchecked.pick)
        assertEquals("the defaults", 600L, unchecked.timeout)
        assertEquals("no", unchecked.home)
        assertFalse(unchecked.network)
        assertTrue(unchecked.files.isEmpty())
        assertTrue("no machine: the phone won't sign it", RootMessage.problem(unchecked) != null)

        assertEquals("rootkey", d.questions[2].kind)
        assertNull(d.questions[2].root)
        assertNull("an ordinary question has no root", DibsView.ofView(view)!!.questions[0].root)
    }

    @Test
    fun theMorningRecapParses() {
        val d = DibsView.parse(
            JSONObject(
                """
                {"recap": {"feed": [], "unread": 2, "needs": 1,
                  "items": [
                    {"key": "t210", "task": 210, "tier": "needs", "title": "The plan is ready", "lede": "Routine work moves to plain code.",
                     "what": "A plan.", "why": "You asked.", "means": "Nothing changes yet.", "next": "Choose.", "project": "dibs", "kind": "plan",
                     "state": "done", "ts": 1760000000, "unread": true, "card": 1093, "story": {"state": "ready", "ts": 1760000100, "have": true}},
                    {"key": "t189", "task": 189, "tier": "asked", "title": "Terminal", "lede": "", "answer": "tmux on the server", "unread": false, "writing": true}
                  ],
                  "small": {"n": 47, "groups": [{"project": "dibs", "n": 21, "lines": ["one", "two"]}, {"project": "tether", "n": 3, "lines": []}]}},
                 "badges": {"recap": 2},
                 "yours": [{"id": 5, "title": "x", "state": "done", "brief": {"tier": "talked", "title": "Five", "unread": true}}],
                 "board": {"columns": [{"key": "now", "title": "Now", "cards": [{"key": "task:6", "n": 6, "title": "Six", "brief": {"task": 6, "tier": "asked", "title": "Six"}}]}]}}
                """,
            ),
        )
        assertTrue(d.recapList)
        val (a, b) = d.recapItems
        assertEquals(Brief("t210", 210, "needs", "The plan is ready", "Routine work moves to plain code.", "A plan.", "You asked.", "Nothing changes yet.", "Choose.",
            null, "dibs", "plan", "done", 1760000000, true, false, 1093, Story("ready", 1760000100, false, true)), a)
        assertEquals("Needs you", a.tag)
        assertTrue(a.needsYou)
        assertEquals("Answers your question", b.tag)
        assertTrue(b.writing)
        assertFalse(b.hasParts)
        assertEquals("tmux on the server", b.answer)
        assertNull(b.card)
        assertEquals(2, d.recapUnread)
        assertTrue(d.recapStories.isEmpty())
        assertEquals(
            listOf(StoryEntry(189, "Terminal", 1760000000, "dibs"), StoryEntry(5, "", 0, null)),
            DibsView.parse(JSONObject("""{"recap": {"items": [], "stories": [{"task": 189, "title": "Terminal", "ts": 1760000000, "project": "dibs"}, {"task": 5}, {"title": "no task"}]}}""")).recapStories,
        )
        assertEquals(1, d.recapNeeds)
        assertEquals(RecapSmall(47, listOf(SmallGroup("dibs", 21, listOf("one", "two")), SmallGroup("tether", 3, emptyList()))), d.recapSmall)
        // A task's own brief and a board card's take their number from the task when the brief has none.
        assertEquals(5L, d.task(5)!!.brief!!.task)
        assertEquals("Six", d.card(6)!!.brief!!.title)
        assertEquals("the recap's own first", 210L, d.brief(210)!!.task)
        assertEquals("Five", d.brief(5)!!.title)
        assertEquals("Six", d.brief(6)!!.title)
        assertNull(d.brief(7))
    }

    @Test
    fun anOlderDibsHasNoItemsAndAnEmptyListIsStillTheNewRecap() {
        val old = DibsView.parse(JSONObject("""{"recap": {"feed": [], "decided": []}, "badges": {"recap": 1}}"""))
        assertFalse(old.recapList)
        assertTrue(old.recapItems.isEmpty())
        assertNull(old.recapSmall)
        assertNull(old.yours)
        val caughtUp = DibsView.parse(JSONObject("""{"recap": {"items": [], "unread": 0, "needs": 0}}"""))
        assertTrue("an empty list is \"caught up\", not the old tab", caughtUp.recapList)
        assertTrue(caughtUp.recapItems.isEmpty())
        assertFalse(DibsView.parse(JSONObject("""{"v": 1}""")).recapList)
        // A brief without its task is dropped; no card or story is fine.
        val odd = DibsView.parse(JSONObject("""{"recap": {"items": [{"title": "no task"}, {"task": 3, "tier": "talked", "title": "T"}]}}"""))
        assertEquals(listOf(3L), odd.recapItems.map { it.task })
        assertEquals("t3", odd.recapItems[0].key)
        assertEquals("You talked it over", odd.recapItems[0].tag)
    }
    @Test
    fun linksStateWordsAndTheIndexRevAreRead() {
        val v = DibsView.parse(
            JSONObject(
                """{"now": 1, "index_rev": "ab12", "yours": [
                  {"id": 5, "title": "T", "state": "working", "state_words": "Working", "links": [
                    {"kind": "idea", "n": 45, "title": "Chat buttons", "state": "Idea", "why": "Made from"},
                    {"kind": "task", "n": 230, "title": "Wait and Go", "state": "Done 8 Oct", "why": "Related"},
                    {"kind": "task", "n": 0, "title": "no number"}]},
                  {"id": 6, "title": "Old", "state": "done"}]}""",
            ),
        )
        assertEquals("ab12", v.indexRev)
        val t = v.task(5)!!
        assertEquals("Working", t.stateWords)
        assertEquals(listOf(RefKind.IDEA to 45, RefKind.TASK to 230), t.links.map { it.kind to it.n })
        assertEquals("Made from", t.links[0].why)
        // An older dibs: nothing of it, nothing breaks.
        assertEquals(emptyList<RefLink>(), v.task(6)!!.links)
        assertEquals("", v.task(6)!!.stateWords)
        assertNull(DibsView.parse(JSONObject("{}")).indexRev)
    }

    @Test
    fun aFetchedPageIsATaskWithItsGroupAndWhoseItIs() {
        val t = DibsView.yourTask(JSONObject("""{"id": 9, "title": "Theirs", "state": "done", "group": "done", "mine": false, "state_words": "Done 8 Oct"}"""))
        assertEquals("done", t.group)
        assertFalse(t.mine)
        assertTrue(DibsView.yourTask(JSONObject("""{"id": 9}""")).mine)
    }
}
