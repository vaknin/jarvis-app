package com.kivan.jarvis.tabs

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kivan.jarvis.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * The reply box of one tab: the reply being written, what Send, Hold and a long press on Send do, and what stands on
 * the box. A thread and the phone's main chat both run on it, so a swipe, a hold, bulk and the release behave the same
 * in both (Aviv: "when I open the Jarvis thread it does work as expected").
 *
 * The reply stays in [marks] as he types, so it's saved like the rest; [replyBefore] is what a cancel goes back to.
 * His words outside a reply live in the caller's box ([typed]).
 */
class ReplyBox internal constructor(private val scope: CoroutineScope, private val buzz: Buzz) {
    var reply by mutableStateOf<QuoteMark?>(null)
        private set
    private var replyBefore by mutableStateOf<QuoteMark?>(null)

    // The caller's inputs, fresh on every composition so a press reads what's on screen now; the ones drawn above
    // the box are state, so the box redraws when they change.
    internal var typed by mutableStateOf("")
    internal var heard = ""
    internal var attached = false
    internal var marks by mutableStateOf<List<QuoteMark>>(emptyList())
    internal var waiting by mutableStateOf(false)
    internal var focus: FocusRequester? = null
    internal var onMarks: (List<QuoteMark>) -> Unit = {}
    internal var onSend: (String, List<Quoted>) -> Unit = { _, _ -> }
    internal var onHoldWords: (String, List<Quoted>) -> Unit = { _, _ -> }
    internal var onRelease: () -> Unit = {}
    internal var onSent: () -> Unit = {}

    /** The open reply's words, or null with none open: the box shows these in place of [typed]. */
    val writing: String? get() = reply?.let { r -> marks.firstOrNull { it.same(r) }?.text.orEmpty() }

    /** Something to send even with no words: a reply, a mark, or what he held. */
    val ready: Boolean get() = reply != null || marks.isNotEmpty() || waiting

    /** Opens a reply on [m] (a swipe, Quote this, a held one tapped). Any other mark stays as it was. */
    fun open(m: QuoteMark) {
        // The reply open before this one is left as Send would leave it: kept with words, gone without.
        reply?.takeIf { !it.same(m) }?.let { mark(closed(keep = true)) }
        val was = marks.firstOrNull { it.same(m) }
        // A fresh one stands in the marks at once, so it's seen and saved; a cancel or no words takes it out again.
        if (was == null) mark(marks.with(m))
        // Another reply left open keeps its words, as the laptop's does.
        reply = was ?: m
        replyBefore = was
        focus?.let { f -> scope.launch { f.requestFocus() } }
    }

    /** The box's words while a reply is open: they write into it. */
    fun write(words: String) {
        val r = reply ?: return
        // Being written, it isn't quiet: only Hold makes it go by itself, so a half-done edit never goes.
        mark(if (words.isBlank()) marks.without(r) else marks.with(r.copy(text = words, quiet = false)))
    }

    /** Leaves the box. Kept words stay held; a cancel, or nothing written, goes back to what was there. */
    fun closed(keep: Boolean): List<QuoteMark> {
        val r = reply ?: return marks
        val before = replyBefore
        val words = writing
        reply = null
        replyBefore = null
        // Emptying a held reply and sending takes it back; only Cancel returns the words.
        if (keep && (words?.isNotBlank() == true || before?.held == true)) return marks
        return if (before != null) marks.with(before) else marks.without(r)
    }

    fun cancel() {
        mark(closed(keep = false))
    }

    private fun mark(next: List<QuoteMark>) {
        if (next == marks) return
        marks = next
        onMarks(next)
    }

    fun send() {
        val words = said(typed, if (reply == null) heard else "")
        val all = closed(keep = true)
        if (words.isNotEmpty() || attached || all.isNotEmpty()) {
            buzz.sent()
            // As on the laptop: each goes as a quote with its own words, or none; his message after them.
            onSend(words, all.map { Quoted(it.to, it.quote, it.text.trim()) })
            onSent()
        } else {
            // A reply opened and left empty goes, as it came.
            mark(all)
            if (waiting) {
                // Nothing typed: what he held goes in now, as the laptop's ctrl+enter.
                buzz.sent()
                onRelease()
            }
        }
    }

    /**
     * Hold: the reply stays here, his to open and edit, and goes quiet by itself at the end of the
     * tab's turn or when he leaves the app, as the laptop's enter; Send takes it along sooner. Held
     * again after an edit, it's the same one: it goes once, with its last words.
     */
    fun hold() {
        val r = reply
        val words = writing?.trim().orEmpty()
        if (r != null && words.isNotEmpty()) {
            reply = null
            replyBefore = null
            mark(marks.with(r.copy(text = words, quiet = true)))
            buzz.sent()
        }
    }

    /**
     * A long press on Send holds, whatever is in the box: an open reply stays here as Hold keeps it; his own
     * words wait on the server, with what he marked, for send-all or a turn that runs anyway. Before, Hold
     * showed only on an open reply with words in it, so a plain message had no quiet way out (t104).
     */
    fun holdAll() {
        if (reply != null) {
            hold()
        } else {
            val words = said(typed, heard)
            if (words.isNotEmpty()) {
                val plain = marks.filterNot { it.held }
                onHoldWords(words, plain.map { Quoted(it.to, it.quote, "") })
                if (plain.isNotEmpty()) mark(marks.filter { it.held })
                buzz.sent()
                // Held, his words still stand in the chat as a message: he sees them land, as on Send.
                onSent()
            }
        }
    }

    /** What stands on the box: the marks waiting, then the reply open with its Hold and its way out. */
    @Composable
    fun Above() {
        val r = reply
        MarkTray(marks.filterNot { r != null && it.same(r) }, onMarks) { open(it) }
        if (r != null) {
            ReplyStrip(r.quote, typed, canHold = !writing.isNullOrBlank(), onHold = { hold() }, onCancel = { cancel() })
        }
    }
}

/**
 * The reply box for one tab. [marks] are the tab's own, [waiting] says it has held words on the server.
 * With a reply open what he says aloud ([heard]) writes into it, and [onTaken] takes it off the mic.
 */
@Composable
fun rememberReplyBox(
    typed: String,
    heard: String,
    marks: List<QuoteMark>,
    waiting: Boolean,
    onMarks: (List<QuoteMark>) -> Unit,
    onSend: (String, List<Quoted>) -> Unit,
    onHoldWords: (String, List<Quoted>) -> Unit,
    onRelease: () -> Unit,
    onTaken: () -> Unit,
    focus: FocusRequester? = null,
    /** A message of his went into the chat, sent or held: the caller shows it. */
    onSent: () -> Unit = {},
): ReplyBox {
    val scope = rememberCoroutineScope()
    val buzz = rememberBuzz()
    val box = remember { ReplyBox(scope, buzz) }
    box.typed = typed
    box.heard = heard
    box.attached = LocalTalk.current.attached.isNotEmpty()
    box.marks = marks
    box.waiting = waiting
    box.focus = focus
    box.onMarks = onMarks
    box.onSend = onSend
    box.onHoldWords = onHoldWords
    box.onRelease = onRelease
    box.onSent = onSent
    // With a reply open his spoken words write into it.
    LaunchedEffect(heard, box.reply) {
        if (heard.isBlank() || box.reply == null) return@LaunchedEffect
        val w = box.writing
        box.write(if (w.isNullOrBlank()) heard else "$w $heard")
        onTaken()
    }
    return box
}

/** What's waiting, on the box: how many of each, like the laptop's line, and each with a way out. */
@Composable
internal fun MarkTray(marks: List<QuoteMark>, onMarks: (List<QuoteMark>) -> Unit, onOpen: (QuoteMark) -> Unit) {
    if (marks.isEmpty()) return
    val quiet = marks.count { it.held }
    val line = listOfNotNull(
        "● ${marks.size - quiet} marked".takeIf { quiet < marks.size },
        "↩ $quiet".takeIf { quiet > 0 },
    ).joinToString("  ")
    Row(Modifier.padding(bottom = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(line, Modifier.padding(start = 8.dp).testTag("marked"), look.soft, style = Words.small, maxLines = 1)
        Row(Modifier.weight(1f).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for (m in marks) key(m.to, m.quote) {
                Row(
                    Modifier
                        .clip(RoundedCornerShape(16.dp))
                        .background(look.surface)
                        .then(if (m.held) Modifier.clickable(onClickLabel = "Edit this reply") { onOpen(m) } else Modifier)
                        .padding(start = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.widthIn(max = 180.dp)) {
                        // Held, it's greyed: waiting, not gone (Aviv, t104).
                        Text(m.quote, color = if (m.held) look.faint else look.soft, style = Words.small, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (m.held) Text(m.text, color = look.soft, style = Words.small, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Round(R.drawable.lucide_x, "Remove the quote: ${m.quote.take(40)}", { onMarks(marks.without(m)) }, size = 38.dp)
                }
            }
        }
    }
}

/** The reply open in the box: what it quotes, Hold to keep it for the next message, and the way out. */
@Composable
internal fun ReplyStrip(quote: String, typed: String, canHold: Boolean, onHold: () -> Unit, onCancel: () -> Unit) {
    // His message waits in the box meanwhile, and goes along if he sends now.
    if (typed.isNotBlank()) {
        Text(typed, Modifier.padding(start = 8.dp, bottom = 4.dp), look.faint, style = Words.small, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
    Row(
        Modifier.padding(bottom = 6.dp).fillMaxWidth().height(IntrinsicSize.Min),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.padding(start = 8.dp).width(2.dp).fillMaxHeight().background(look.ink, RoundedCornerShape(1.dp)))
        Text(quote, Modifier.weight(1f).padding(start = 10.dp).testTag("replying"), look.soft, style = Words.small, maxLines = 2, overflow = TextOverflow.Ellipsis)
        if (canHold) {
            Box(
                Modifier
                    .padding(start = 8.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(look.surface)
                    .clickable(onClick = onHold)
                    .semantics { role = Role.Button }
                    .heightIn(min = 38.dp)
                    .padding(horizontal = 14.dp),
                Alignment.Center,
            ) { Text("Hold", color = look.ink, style = Words.small) }
        }
        Round(R.drawable.lucide_x, "Cancel this reply", onCancel, size = 38.dp)
    }
}
