package com.kivan.tether.dibs

import org.json.JSONArray
import org.json.JSONObject

// The payload dibs publishes in its channel's view (`"dibs": {…}`, docs/DIBS-APP.md), parsed once
// per view. Every field is optional on the wire: an older or newer dibs still draws.

data class Action(val id: String, val label: String, val style: String = "")

data class FileRef(val id: String, val name: String, val size: Long, val image: Boolean)

/** A question asked in the chat: its buttons while open, then how it ended. */
data class Ask(val q: Long, val actions: List<Action>, val reply: String?, val outcome: String?) {
    val open: Boolean get() = outcome == null
}

data class TalkLine(
    /** The phone's uid for the user's lines (their pending echo clears on it), else `s<n>`. */
    val id: String,
    /** dibs's line number: `h<n>` hides it. */
    val n: Long,
    val mine: Boolean,
    val text: String,
    /** What a note shows until a tap opens [text]. */
    val short: String?,
    /** dibs's own note (a confirmation), drawn small. */
    val note: Boolean,
    val ts: Long,
    val files: List<FileRef>,
    val ask: Ask?,
)

data class Question(
    val id: Long,
    val title: String,
    val why: String,
    val details: String?,
    val from: String,
    val repo: String?,
    val ts: Long,
    val blocking: Boolean,
    /** question | phone | update | permission | idea | task */
    val kind: String,
    val actions: List<Action>,
    /** The typed answer's action id (`r<id>`), when it takes one. */
    val reply: String?,
    val phoneSecs: Long?,
    val phoneUnlock: Boolean,
)

data class Decision(val id: Long, val text: String, val why: String, val from: String, val ts: Long, val undo: Boolean, val ack: String)

data class Task(
    val id: Long,
    val name: String,
    val state: String,
    val repo: String?,
    /** How long it has run, from its start (dibs sends no clock, so its views stay still). */
    val minutes: Long,
    val text: String,
    val doing: String?,
    /** busy | idle | shell, when its session is live. */
    val status: String?,
    /** Its live session's pid: what a peek asks for. */
    val pid: Long? = null,
)

data class Session(val name: String, val repo: String?, val branch: String?, val status: String, val task: String?, val holds: List<String>)

data class Ship(val repo: String, val who: String, val why: String, val since: Long)

data class Peek(val who: String, val at: Long, val lines: List<String>)

data class Away(val id: Long, val title: String, val lines: List<String>, val since: Long, val until: Long)

data class FeedItem(val ts: Long, val kind: String, val who: String, val text: String)

data class Lend(val until: Long?, val holder: String?, val text: String)

/** One lend toggle (task #29): lent to dibs or the user's, what it says, and the action that flips it. */
data class LendToggle(val lent: Boolean, val text: String, val action: String)

/** The phone's and the laptop's toggles; null in a payload from a dibs without them. */
data class Lends(val phone: LendToggle?, val laptop: LendToggle?)

data class State(val brain: String?, val busy: Boolean, val line: String?, val usage: String?)

data class Badges(val waiting: Int, val work: Int, val recap: Int)

data class DibsView(
    /** dibs's clock when it published. */
    val now: Long,
    val state: State,
    val talk: List<TalkLine>,
    val questions: List<Question>,
    val decided: List<Decision>,
    val tasks: List<Task>,
    val sessions: List<Session>,
    val ships: List<Ship>,
    val peek: Peek?,
    val away: Away?,
    val feed: List<FeedItem>,
    val lend: Lend?,
    val lends: Lends?,
    val badges: Badges,
) {
    companion object {
        /** The payload of a whole channel view, or null when dibs sent none (an older dibs). */
        fun ofView(view: JSONObject?): DibsView? = view?.optJSONObject("dibs")?.let(::parse)

        fun parse(o: JSONObject): DibsView {
            val st = o.optJSONObject("state") ?: JSONObject()
            val recap = o.optJSONObject("recap") ?: JSONObject()
            val b = o.optJSONObject("badges") ?: JSONObject()
            return DibsView(
                now = o.optLong("now"),
                state = State(st.str("brain"), st.optBoolean("busy"), st.str("line"), st.str("usage")),
                talk = o.optJSONArray("talk").objects().map(::talkLine),
                questions = o.optJSONArray("questions").objects().map(::question),
                decided = o.optJSONArray("decided").objects().map {
                    Decision(it.optLong("id"), it.optString("text"), it.optString("why"), it.optString("from"), it.optLong("ts"), it.optBoolean("undo"), it.optString("ack"))
                },
                tasks = o.optJSONArray("tasks").objects().map {
                    val started = it.optLong("started")
                    val minutes = if (started > 0) (System.currentTimeMillis() / 1000 - started) / 60 else it.optLong("minutes")
                    Task(it.optLong("id"), it.optString("name"), it.optString("state"), it.str("repo"), minutes, it.optString("text"), it.str("doing"), it.str("status"), it.optLong("pid").takeIf { p -> p > 0 })
                },
                sessions = o.optJSONArray("sessions").objects().map {
                    Session(it.optString("name"), it.str("repo"), it.str("branch"), it.optString("status"), it.str("task"), it.optJSONArray("holds").strings())
                },
                ships = o.optJSONArray("ships").objects().map { Ship(it.optString("repo"), it.optString("who"), it.optString("why"), it.optLong("since")) },
                peek = o.optJSONObject("peek")?.let { Peek(it.optString("who"), it.optLong("at"), it.optJSONArray("lines").strings()) },
                away = recap.optJSONObject("away")?.let {
                    Away(it.optLong("id"), it.optString("title"), it.optJSONArray("lines").strings(), it.optLong("since"), it.optLong("until"))
                },
                feed = recap.optJSONArray("feed").objects().map { FeedItem(it.optLong("ts"), it.optString("kind"), it.optString("who"), it.optString("text")) },
                lend = o.optJSONObject("lend")?.let { Lend(it.optLong("until").takeIf { u -> u > 0 }, it.str("holder"), it.optString("text")) },
                lends = o.optJSONObject("lends")?.let { l -> Lends(l.optJSONObject("phone")?.let(::lendToggle), l.optJSONObject("laptop")?.let(::lendToggle)) },
                badges = Badges(b.optInt("waiting"), b.optInt("work"), b.optInt("recap")),
            )
        }

        private fun lendToggle(o: JSONObject): LendToggle? =
            o.optString("action").takeIf { it.isNotBlank() }?.let { LendToggle(o.optBoolean("lent"), o.optString("text"), it) }

        private fun talkLine(o: JSONObject) = TalkLine(
            id = o.optString("id"),
            n = o.optLong("n"),
            mine = o.optString("who") == "user",
            text = o.optString("text"),
            short = o.str("short"),
            note = o.optBoolean("note"),
            ts = o.optLong("ts"),
            files = o.optJSONArray("files").objects().map { FileRef(it.optString("id"), it.optString("name"), it.optLong("size"), it.optBoolean("image")) },
            ask = o.optJSONObject("ask")?.let { a ->
                Ask(a.optLong("q"), actions(a.optJSONArray("actions")), a.str("reply"), a.str("outcome"))
            },
        )

        private fun question(o: JSONObject): Question {
            val phone = o.optJSONObject("phone")
            return Question(
                id = o.optLong("id"),
                title = o.optString("title"),
                why = o.optString("why"),
                details = o.str("details"),
                from = o.optString("from"),
                repo = o.str("repo"),
                ts = o.optLong("ts"),
                blocking = o.optBoolean("blocking"),
                kind = o.optString("kind", "question"),
                actions = actions(o.optJSONArray("actions")),
                reply = o.str("reply"),
                phoneSecs = phone?.optLong("secs"),
                phoneUnlock = phone?.optBoolean("unlock") == true,
            )
        }

        private fun actions(a: JSONArray?) = a.objects().map { Action(it.optString("id"), it.optString("label"), it.optString("style")) }
    }
}

/** A string field, or null when it's missing, null or empty. */
private fun JSONObject.str(k: String): String? = if (isNull(k)) null else optString(k).takeIf { it.isNotEmpty() }

private fun JSONArray?.objects(): List<JSONObject> = if (this == null) emptyList() else (0 until length()).mapNotNull { optJSONObject(it) }

private fun JSONArray?.strings(): List<String> = if (this == null) emptyList() else (0 until length()).mapNotNull { opt(it)?.toString() }
