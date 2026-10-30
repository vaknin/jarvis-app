package com.kivan.jarvis.forum

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Person
import android.app.RemoteInput
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.net.Uri
import android.text.Html
import android.util.Log
import com.kivan.jarvis.LinkService
import com.kivan.jarvis.R
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.time.Instant
import java.util.TreeMap
import java.util.TreeSet
import java.util.UUID

/**
 * The forum's notifications without Google or Firebase (thread #112), started with the process ([JarvisApp]);
 * [LinkService]'s foreground service keeps it up for the tunnel. This long-polls Discourse's message bus through it, as the
 * forum's own page does. Only what's aimed at Aviv notifies, as Discourse itself reckons it (thread #112: "not
 * important: no notif / somewhat: notif / important: call"): a mention or a private message (Discourse's own
 * unread notifications), every message in a direct chat, and a chat message that mentions him. One notification
 * per thread or chat channel, holding those posts. Everything else unread in the inbox (thread #88) and in
 * chat only shows in home's What's new ([waiting]). News on the bus is only a cue: each sync reads the read
 * state again and derives what to show, so a post read anywhere (a swipe in the inbox, a reply, the laptop)
 * leaves the phone too. Nothing here marks anything read on the forum, its notifications included: opening or
 * dismissing a notification only stops the phone showing those posts again (thread #103).
 */
object Watch {
    const val POSTS = "posts"
    const val CHAT = "chat"
    private const val TAG = "ForumWatch"
    private const val FULL_MS = 10 * 60_000L // a full sync this often, in case the bus missed something
    private const val SHOWN = 8 // posts or messages kept in one notification
    /** Discourse's notification types aimed at Aviv: mentioned, private message, invited to one, group mentioned. */
    private val AIMED = setOf(1, 6, 7, 15)

    class Msg(val key: Long, val user: String, val name: String, val text: String, val time: Long)
    /** A line of What's new: an unread thread ("t") or chat channel ("c"), its newest post from someone else. */
    class Item(val kind: String, val id: Long, val title: String, val text: String, val time: Long, val needsYou: Boolean)
    private class Shown(val title: String, val msgs: List<Msg>)

    private lateinit var app: Context
    private lateinit var http: Http
    private val wake = Object()
    @Volatile private var started = false
    private var uid = 0L
    private var username = ""
    private val last = HashMap<String, Long>() // each bus channel's last message id
    private var chatChannels = listOf<Long>()
    private val titles = HashMap<Long, String>()
    private val posts = HashMap<Long, TreeMap<Long, Msg>>() // topic → post number → post, as fetched
    private val covered = HashMap<Long, MutableList<LongRange>>() // topic → the post numbers fetched (small actions leave gaps)
    private val shown = HashMap<String, Shown>() // "t123" / "c4" → what its notification holds
    private var lastFull = 0L
    private var needsYou = 0L // the needs-you tag's id (0: the tag isn't visible to this user)
    @Volatile private var threadItems = listOf<Item>()
    @Volatile private var chatItems = listOf<Item>()
    private val _waiting = MutableStateFlow<List<Item>>(emptyList())
    /** What's new: unread threads (needs-you first) and chats, newest first. */
    val waiting: StateFlow<List<Item>> = _waiting
    private val _status = MutableStateFlow("")
    /** The service's line: whether notifications are on, or what they wait for. */
    val status: StateFlow<String> = _status
    /** The app is on screen: new posts update their notifications quietly, as the forum's own tab would. */
    @Volatile var foreground = false

    fun log(s: String) = Log.i(TAG, s)

    fun start(context: Context) {
        synchronized(this) {
            if (started) return
            started = true
            app = context.applicationContext
            http = Session.load(app)
        }
        val nm = app.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(POSTS, "Threads", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "A new post in a thread you follow, as in the inbox"
        })
        nm.createNotificationChannel(NotificationChannel(CHAT, "Chat", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "A new chat message"
        })
        Thread(::loop, "forum-watch").apply { isDaemon = true }.start()
    }

    /** A new session (the WebView just logged in): stop waiting and use it. */
    fun kick() = synchronized(wake) { wake.notifyAll() }

    /** Waits up to `ms`, or less if a session turns up (checked under the lock, so a kick can't slip by). */
    private fun pause(ms: Long) = synchronized(wake) { if (!Session.has(app)) wake.wait(ms) }

    private fun loop() {
        val client = UUID.randomUUID().toString().replace("-", "")
        var backoff = 2_000L
        while (true) {
            // The new app has the link ([Move]): its port answers here too, and it notifies.
            while (com.kivan.jarvis.Move.off()) Thread.sleep(2_000)
            try {
                if (uid == 0L) {
                    if (!Session.has(app)) {
                        // None yet, or logged out in Security: make a new one from the WebView's, if it has one.
                        status("Log in to the forum once to get notifications")
                        Login.maybe(app)
                        pause(60_000)
                        continue
                    }
                    http = Session.load(app)
                    if (!whoami()) continue
                    status("Notifications on")
                    sync(posts = true, chat = true)
                    if (gone()) continue
                }
                val form = channels().associateWith { (last[it] ?: -1L).toString() }
                val r = http.request("POST", "/message-bus/$client/poll", form, timeoutMs = 60_000)
                // A revoked session gets 403 from the bus with its cookie kept (seen 2026-10-28): check it.
                if (r.code == 403 || r.code == 404) {
                    refused("bus", r.code)
                    if (uid == 0L) continue
                }
                if (!r.ok) throw IOException("bus ${r.code}")
                if (gone()) continue
                val moved = take(JSONArray(r.body))
                backoff = 2_000L
                val chat = moved.any { it.startsWith("/chat/") }
                val post = moved.any { it.startsWith("/unread") || it.startsWith("/notification/") }
                // A new thread: jarvis-quiet marks it read for Aviv a moment later unless it mentions him.
                if (moved.contains("/new") && !post) Thread.sleep(2_000)
                val full = System.currentTimeMillis() - lastFull > FULL_MS
                if (post || chat || full || moved.contains("/new")) {
                    sync(posts = post || full || moved.contains("/new"), chat = chat || full)
                    gone()
                }
            } catch (e: Exception) {
                log("watch: $e")
                Thread.sleep(backoff)
                backoff = (backoff * 2).coerceAtMost(60_000L)
            }
        }
    }

    private fun status(line: String) {
        _status.value = line
        LinkService.status(app, line)
    }

    /**
     * Whether the session was logged out (in Preferences > Security, or it expired): Discourse then answers as
     * to a guest and takes the cookie back. The session is dropped, and the loop makes a new one from the WebView's.
     */
    private fun gone(): Boolean {
        if (uid != 0L && "_t" in synchronized(http.jar) { http.jar.keys.toSet() }) return false
        if (uid != 0L) log("session logged out; a new one from the WebView's")
        Session.clear(app)
        uid = 0L
        return true
    }

    /** Who the session is. A session the forum no longer knows (logged out in Security) is dropped. */
    private fun whoami(): Boolean {
        val r = http.get("/session/current.json")
        if (r.code == 404 || r.code == 403) {
            log("session gone (${r.code}); waiting for the WebView's")
            Session.clear(app)
            uid = 0L
            return false
        }
        if (!r.ok) throw IOException("current ${r.code}")
        val u = r.json().getJSONObject("current_user")
        uid = u.getLong("id")
        username = u.getString("username")
        needsYou = runCatching {
            val tags = http.get("/tags.json").json().getJSONArray("tags")
            (0 until tags.length()).map { tags.getJSONObject(it) }.first { it.optString("name") == "needs-you" }.getLong("id")
        }.getOrDefault(0L)
        last.clear()
        log("watching as $username")
        return true
    }

    /**
     * A sync answered 403/404: either the session was logged out (dropped, so the loop makes a new one) or only
     * that page refuses (the session stays; dropping it would mint a new one in Aviv's Security list each time).
     */
    private fun refused(what: String, code: Int) {
        val r = http.get("/session/current.json")
        if (r.code != 403 && r.code != 404) {
            log("$what $code; the session is still good")
            return
        }
        log("session gone ($what $code); a new one from the WebView's")
        Session.clear(app)
        uid = 0L
    }

    private fun channels() = listOf(
        "/unread", "/new", "/unread/$uid", "/notification/$uid",
        "/chat/user-tracking-state/$uid", "/chat/bulk-user-tracking-state/$uid", "/chat/new-channel",
    ) + chatChannels.map { "/chat/$it/new-messages" }

    /** Moves each channel's mark past the bus's answer; returns the channels with real news. */
    private fun take(msgs: JSONArray): Set<String> {
        val moved = mutableSetOf<String>()
        for (i in 0 until msgs.length()) {
            val m = msgs.getJSONObject(i)
            val channel = m.getString("channel")
            if (channel == "/__status") {
                val d = m.getJSONObject("data")
                d.keys().forEach { last[it] = d.getLong(it) }
            } else {
                last[channel] = m.getLong("message_id")
                moved += channel
            }
        }
        return moved
    }

    private fun sync(posts: Boolean, chat: Boolean) {
        if (posts) syncPosts()
        if (chat) syncChat()
        if (posts && chat) lastFull = System.currentTimeMillis()
    }

    private fun prefs() = app.getSharedPreferences("watch", Context.MODE_PRIVATE)

    /**
     * One look at the forum, for two things. What's new: the threads the inbox shows, new or unread in Discourse's
     * own tracking state, as its page reckons them, each with its newest post. Notifications: only the posts aimed
     * at Aviv, from Discourse's own unread notifications ([AIMED]), so a plain post, or a reply to his, waits in the
     * inbox without ringing, and reading it anywhere marks its notification read and takes ours down.
     */
    private fun syncPosts() {
        val r = http.get("/u/$username/topic-tracking-state.json")
        if (r.code == 403 || r.code == 404) return refused("tracking", r.code)
        if (!r.ok) throw IOException("tracking ${r.code}")
        val rows = JSONArray(r.body)
        val want = HashMap<Long, Pair<Long, Long>>() // topic → (last read, highest)
        val tagged = HashSet<Long>()
        val created = HashMap<Long, Long>()
        for (i in 0 until rows.length()) {
            val t = rows.getJSONObject(i)
            val read = if (t.isNull("last_read_post_number")) null else t.getLong("last_read_post_number")
            val level = if (t.isNull("notification_level")) null else t.getInt("notification_level")
            val highest = t.getLong("highest_post_number")
            val isNew = read == null && (level == null || level >= 2) && t.optBoolean("created_in_new_period")
            val isUnread = read != null && read < highest && level != null && level >= 2
            if (!isNew && !isUnread) continue
            val id = t.getLong("topic_id")
            want[id] = (read ?: 0L) to highest
            val tags = t.optJSONArray("tags")
            if (needsYou != 0L && tags != null && (0 until tags.length()).any { tags.optJSONObject(it)?.optLong("id") == needsYou }) tagged += id
            runCatching { created[id] = Instant.parse(t.getString("created_at")).toEpochMilli() }
        }
        // silent: not even the bell's "seen" mark moves; reading the list never marks a notification read.
        val n = http.get("/notifications.json?filter=unread&silent=true&username=${Http.enc(username)}")
        if (n.code == 403 || n.code == 404) return refused("notifications", n.code)
        if (!n.ok) throw IOException("notifications ${n.code}")
        val list = n.json().getJSONArray("notifications")
        val aimed = HashMap<Long, TreeSet<Long>>() // topic → the post numbers aimed at Aviv
        for (i in 0 until list.length()) {
            val o = list.getJSONObject(i)
            if (o.optBoolean("read") || o.optInt("notification_type") !in AIMED || o.isNull("topic_id")) continue
            val t = o.getLong("topic_id")
            aimed.getOrPut(t) { TreeSet() } += o.optLong("post_number", 1L)
            o.optJSONObject("data")?.optString("topic_title")?.takeIf { it.isNotEmpty() }?.let { titles.putIfAbsent(t, it) }
        }
        val topics = want.keys + aimed.keys
        posts.keys.retainAll(topics)
        covered.keys.retainAll(topics)
        // What's new: the inbox's threads, and one aimed at Aviv outside it (a private message).
        threadItems = topics.map { t ->
            val (read, highest) = want[t] ?: ((aimed.getValue(t).first() - 1) to aimed.getValue(t).last())
            val newest = fetchPosts(t, maxOf(read + 1, highest - SHOWN + 1), highest)?.values?.lastOrNull { it.user != username }
            Item("t", t, titles[t] ?: "Thread #$t", newest?.let { "${it.name}: ${it.text}" } ?: "", newest?.time ?: created[t] ?: 0L, t in tagged)
        }
        publish()
        val fresh = HashMap<Long, Pair<String, List<Msg>>>()
        for ((t, numbers) in aimed) {
            val from = prefs().getLong("d.t$t", 0L)
            val nums = numbers.filter { it > from }.takeLast(SHOWN)
            if (nums.isEmpty()) continue
            // A fetch that failed keeps what the notification already shows, rather than taking it down.
            val got = fetchPosts(t, nums.first(), nums.last())?.let { m -> nums.mapNotNull { m[it] } } ?: shown["t$t"]?.msgs ?: continue
            val msgs = got.filter { it.key > from && it.user != username }
            if (msgs.isNotEmpty()) fresh[t] = (titles[t] ?: "Thread #$t") to msgs
        }
        show("t", aimed.keys, fresh, baselineKey = "baseline.t")
    }

    private fun publish() {
        _waiting.value = (threadItems + chatItems).sortedWith(compareByDescending<Item> { it.needsYou }.thenByDescending { it.time })
    }

    /** Posts from..to of a thread, from the cache or one page of the thread around `from`. Reads nothing as read. */
    private fun fetchPosts(t: Long, from: Long, to: Long): Map<Long, Msg>? {
        val cache = posts.getOrPut(t) { TreeMap() }
        val c = covered.getOrPut(t) { mutableListOf() }
        if (c.none { from >= it.first && to <= it.last }) {
            val r = http.get("/t/$t/$from.json")
            if (!r.ok) return null
            c += from..to
            val j = r.json()
            titles[t] = Html.fromHtml(j.optString("fancy_title", j.optString("title")), Html.FROM_HTML_MODE_COMPACT).toString()
            val list = j.getJSONObject("post_stream").getJSONArray("posts")
            for (i in 0 until list.length()) {
                val p = list.getJSONObject(i)
                if (p.optInt("post_type", 1) !in setOf(1, 4)) continue // small actions and the like
                cache[p.getLong("post_number")] = Msg(
                    p.getLong("post_number"), p.getString("username"),
                    p.optString("name").ifBlank { p.getString("username") },
                    plain(p.optString("cooked")), Instant.parse(p.getString("created_at")).toEpochMilli(),
                )
            }
        }
        return cache.subMap(from, true, to, true)
    }

    /**
     * The chat channels Aviv follows (not muted): each with unread messages from others is in What's new; a direct
     * chat notifies with them all, a channel only with the ones that mention him (@him, @here, @all).
     */
    private fun syncChat() {
        val r = http.get("/chat/api/me/channels")
        if (r.code == 403 || r.code == 404) return refused("chat", r.code)
        if (!r.ok) throw IOException("chat ${r.code}")
        val j = r.json()
        val tracking = j.optJSONObject("tracking")?.optJSONObject("channel_tracking") ?: JSONObject()
        val all = (0 until j.getJSONArray("public_channels").length()).map { j.getJSONArray("public_channels").getJSONObject(it) } +
            (0 until j.getJSONArray("direct_message_channels").length()).map { j.getJSONArray("direct_message_channels").getJSONObject(it) }
        val followed = all.filter { c ->
            val m = c.optJSONObject("current_user_membership")
            m != null && m.optBoolean("following") && !m.optBoolean("muted")
        }
        chatChannels = followed.map { it.getLong("id") }
        val mention = Regex("(^|[^\\w@])@(${Regex.escape(username)}|here|all)\\b", RegexOption.IGNORE_CASE)
        val want = mutableSetOf<Long>()
        val fresh = HashMap<Long, Pair<String, List<Msg>>>()
        val items = mutableListOf<Item>()
        for (c in followed) {
            val id = c.getLong("id")
            val t = tracking.optJSONObject("$id")
            val unread = t?.optInt("unread_count") ?: 0
            if (unread == 0) continue
            val dm = c.optString("chatable_type") == "DirectMessage"
            val m = c.getJSONObject("current_user_membership")
            val read = if (m.isNull("last_read_message_id")) 0L else m.getLong("last_read_message_id")
            val page = http.get("/chat/api/channels/$id/messages?page_size=20")
            if (!page.ok) continue
            val list = page.json().getJSONArray("messages")
            val others = (0 until list.length()).map { list.getJSONObject(it) }
                .filter { it.getLong("id") > read && it.getJSONObject("user").getLong("id") != uid }
            fun msg(it: JSONObject): Msg {
                val u = it.getJSONObject("user")
                return Msg(
                    it.getLong("id"), u.getString("username"), u.optString("name").ifBlank { u.getString("username") },
                    plain(it.optString("cooked")).ifBlank { it.optString("message") }, // rendered, not raw Markdown
                    Instant.parse(it.getString("created_at")).toEpochMilli(),
                )
            }
            others.lastOrNull()?.let(::msg)?.let { items += Item("c", id, c.optString("title").ifBlank { "Chat" }, "${it.name}: ${it.text}", it.time, false) }
            if (!dm && (t?.optInt("mention_count") ?: 0) == 0) continue
            want += id
            val from = maxOf(read, prefs().getLong("d.c$id", 0L))
            val msgs = others.filter { it.getLong("id") > from && (dm || mention.containsMatchIn(it.optString("message"))) }
                .map(::msg)
                .takeLast(minOf(unread, SHOWN))
            // A one-to-one DM has no title of its own: the sender's name is the notification's ("Jarvis", not "Jarvis: Jarvis").
            val oneToOne = dm && (c.optJSONObject("chatable")?.optJSONArray("users")?.length() ?: 0) <= 1
            if (msgs.isNotEmpty()) fresh[id] = (if (oneToOne) "" else c.optString("title").ifBlank { "Chat" }) to msgs
        }
        chatItems = items
        publish()
        show("c", want, fresh, baselineKey = "baseline.c")
    }

    /**
     * Shows `fresh`, and takes down the kind's notifications that aren't in it. A post past the last one
     * alerted alerts; anything else updates quietly. The first sync ever only notes where things stand, so
     * installing the app doesn't ring for everything already in the inbox.
     */
    @Synchronized
    private fun show(kind: String, waiting: Set<Long>, fresh: Map<Long, Pair<String, List<Msg>>>, baselineKey: String) {
        val p = prefs()
        val edit = p.edit()
        val first = !p.getBoolean(baselineKey, false)
        val nm = app.getSystemService(NotificationManager::class.java)
        // Marks of threads no longer waiting go, so the next post there starts afresh.
        p.all.keys.filter { (it.startsWith("d.$kind") || it.startsWith("a.$kind")) && it.drop(3).toLongOrNull() !in waiting }
            .forEach { edit.remove(it) }
        if (first) {
            fresh.forEach { (id, v) -> edit.putLong("d.$kind$id", v.second.last().key).putLong("a.$kind$id", v.second.last().key) }
            edit.putBoolean(baselineKey, true).apply()
            log("baseline $kind: ${fresh.size} waiting, none shown")
            return
        }
        for ((id, v) in fresh) {
            val title = v.first
            // Again under the lock: a notification dismissed while this sync was fetching stays dismissed.
            val msgs = v.second.filter { it.key > p.getLong("d.$kind$id", 0L) }
            if (msgs.isEmpty()) continue
            val top = msgs.last().key
            val news = top > p.getLong("a.$kind$id", 0L)
            val alert = news && !foreground
            val before = shown["$kind$id"]
            val active = nm.activeNotifications.any { it.tag == kind && it.id == id.toInt() }
            if (!alert && active && before != null && before.msgs.last().key == top && before.title == title) continue
            if (news) edit.putLong("a.$kind$id", top)
            post(kind, id, title, msgs, alert)
        }
        for (n in nm.activeNotifications) {
            if (n.tag == kind && fresh[n.id.toLong()]?.second?.any { it.key > p.getLong("d.$kind${n.id}", 0L) } != true) {
                nm.cancel(kind, n.id)
                shown.remove("$kind${n.id}")
            }
        }
        edit.apply()
    }

    private fun post(kind: String, id: Long, title: String, msgs: List<Msg>, alert: Boolean, note: String? = null) {
        shown["$kind$id"] = Shown(title, msgs)
        val me = Person.Builder().setName("You").setKey("me").build()
        val style = Notification.MessagingStyle(me).setConversationTitle(title.ifEmpty { null }).setGroupConversation(title.isNotEmpty())
        msgs.takeLast(SHOWN).forEach {
            style.addMessage(Notification.MessagingStyle.Message(it.text, it.time, Person.Builder().setName(it.name).setKey(it.user).build()))
        }
        note?.let { style.addMessage(Notification.MessagingStyle.Message(it, System.currentTimeMillis(), null as Person?)) }
        val top = msgs.last().key
        val open = PendingIntent.getActivity(
            app, 0,
            Intent(app, ForumActivity::class.java).setAction(Intent.ACTION_VIEW).setData(Uri.parse("forum-app://open/$kind/$id/$top")),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val dismiss = PendingIntent.getBroadcast(
            app, 0,
            Intent(app, Actions::class.java).setAction(Actions.DISMISS).setData(Uri.parse("forum-app://dismiss/$kind/$id/$top")),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        // Mutable: the reply's text is filled in by the system. The intent is explicit, so nothing else can take it.
        val reply = PendingIntent.getBroadcast(
            app, 0,
            Intent(app, Actions::class.java).setAction(Actions.REPLY).setData(Uri.parse("forum-app://reply/$kind/$id/$top")),
            PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val action = Notification.Action.Builder(Icon.createWithResource(app, R.drawable.ic_notification), "Reply", reply)
            .addRemoteInput(RemoteInput.Builder(Actions.TEXT).setLabel("Reply").build())
            .setSemanticAction(Notification.Action.SEMANTIC_ACTION_REPLY)
            .setAllowGeneratedReplies(true)
            .build()
        val n = Notification.Builder(app, if (kind == "t") POSTS else CHAT)
            .setSmallIcon(R.drawable.ic_notification)
            .setStyle(style)
            .setContentIntent(open)
            .setDeleteIntent(dismiss)
            .setAutoCancel(true)
            .setNumber(msgs.size)
            .setCategory(Notification.CATEGORY_MESSAGE)
            .setWhen(msgs.last().time)
            .setShowWhen(true)
            // The platform builder has no setSilent: a group child set to alert only with its summary (never posted) is silent.
            .setGroup("forum-$kind")
            .setGroupAlertBehavior(if (alert) Notification.GROUP_ALERT_ALL else Notification.GROUP_ALERT_SUMMARY)
            .addAction(action)
            .build()
        app.getSystemService(NotificationManager::class.java).notify(kind, id.toInt(), n)
        log("${if (alert) "alert" else "quiet"} $kind$id: ${msgs.size} up to $top")
    }

    /** A notification opened or dismissed: the phone doesn't show those posts again. The forum isn't told. */
    @Synchronized
    fun seen(context: Context, kind: String, id: Long, upTo: Long) {
        start(context)
        val p = prefs()
        if (upTo > p.getLong("d.$kind$id", 0L)) p.edit().putLong("d.$kind$id", upTo).apply()
        app.getSystemService(NotificationManager::class.java).cancel(kind, id.toInt())
        shown.remove("$kind$id")
    }

    /** A reply from the notification, posted as the session's user. Discourse then moves the read mark itself. */
    fun reply(context: Context, kind: String, id: Long, upTo: Long, text: String) {
        start(context)
        val r = try {
            if (kind == "t") {
                http.post("/posts.json", mapOf("topic_id" to "$id", "raw" to text, "reply_to_post_number" to "$upTo"))
            } else {
                http.post("/chat/$id.json", mapOf("message" to text))
            }
        } catch (e: Exception) {
            null
        }
        if (r?.ok == true) {
            log("replied in $kind$id")
            seen(context, kind, id, upTo)
            return
        }
        val why = r?.let { "${it.code} ${it.body.take(120)}" } ?: "no connection"
        log("reply failed in $kind$id: $why")
        synchronized(this) {
            val s = shown["$kind$id"]
            if (s != null && s.msgs.isNotEmpty()) return post(kind, id, s.title, s.msgs, alert = false, note = "Not sent ($why): $text")
            // The process restarted since (nothing in memory): still say so, so the text isn't lost.
            val n = Notification.Builder(app, if (kind == "t") POSTS else CHAT)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle("Reply not sent ($why)")
                .setContentText(text)
                .setStyle(Notification.BigTextStyle().bigText(text))
                .build()
            app.getSystemService(NotificationManager::class.java).notify(kind, id.toInt(), n)
        }
    }

    /** A post's cooked HTML as notification text. */
    private fun plain(html: String) =
        Html.fromHtml(html, Html.FROM_HTML_MODE_COMPACT).toString().replace('￼', ' ').replace(Regex("\n{3,}"), "\n\n").trim().take(1000)
}
