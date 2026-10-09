package com.kivan.tether.dibs.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kivan.tether.dibs.Dibs
import com.kivan.tether.dibs.DibsView
import com.kivan.tether.dibs.R
import com.kivan.tether.dibs.ui.theme.AppType
import com.kivan.tether.dibs.ui.theme.Palette
import com.kivan.tether.dibs.ui.theme.Space
import kotlinx.coroutines.delay

// The Chat tab (docs/DIBS-APP.md, "Chat"): the conversation with dibs from the bottom, the box
// pinned under it. Its parts are shared with each task's chat (ChatParts.kt).

@Composable
internal fun ChatTab(view: DibsView) {
    val all by Dibs.pending.collectAsStateWithLifecycle()
    // The echoes of messages to dibs; a task's are in its own chat.
    val pending = remember(all) { all.filter { it.task == null && it.ask == null } }
    val hidden = Dibs.hidden.keys.toSet()
    val talk = remember(view.talk, hidden) { view.talk.filter { it.id !in hidden } }
    // A note that a task's full story is ready opens it ("Read it") while the task is listed.
    // A line standing for an Ask about conversation draws that conversation's row.
    val look = remember(view.yours, view.asks, view.questions) {
        ChatLook(
            stories = view.yours?.mapTo(HashSet()) { it.id }.orEmpty(),
            asks = view.asks.associateBy { it.about },
            replies = true,
            roots = view.questions.filter { (it.kind == "root" && it.root != null) || it.kind == "rootkey" }.associateBy { it.id },
        )
    }
    val rows = rememberChatRows(talk, pending, look)
    // The chat is on screen with the app in front: its newest line is read (the laptop's bar count follows).
    val newest = talk.maxOfOrNull { it.n } ?: 0L
    LifecycleResumeEffect(newest) {
        Dibs.chatSeen(newest)
        onPauseOrDispose { }
    }
    val echoes = remember(pending) { pending.associateBy { it.uid } }
    Column(Modifier.fillMaxSize()) {
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (rows.isEmpty()) {
                Text(
                    "Say hello. Photos and files can go along too.",
                    style = AppType.body,
                    color = Palette.Muted,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.align(Alignment.Center).padding(Space.XL),
                )
            }
            Conversation(rows, echoes, busy = view.state.busy, busyLine = view.state.line, look = look, state = Dibs.chatList, pin = Dibs.chatPin, keepPlace = true)
        }
        HoldBar(view)
        InputArea(Dibs.chat, "Message dibs")
    }
}

/**
 * Over the box (task #69, #224): dibs's Wait/Go chip, words and all (`state.hold`): a one-line note
 * and its button, with a pause icon for Wait and a play icon for Go. One merged node, so TalkBack
 * reads the note and the button together.
 */
@Composable
private fun HoldBar(view: DibsView) {
    val tap = Dibs.holdTap
    LaunchedEffect(tap) {
        if (tap == null) return@LaunchedEffect
        delay(Dibs.TAP_MS)
        if (Dibs.holdTap == tap) Dibs.holdTap = null
    }
    val hold = Dibs.hold(view) ?: return
    val icon = when (hold.kind) {
        "wait" -> R.drawable.lucide_pause
        "held" -> R.drawable.lucide_play
        else -> null
    }
    Row(
        Modifier.fillMaxWidth().padding(start = 16.dp, end = 12.dp, top = 4.dp).semantics(mergeDescendants = true) {},
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Space.S),
    ) {
        if (hold.note != null) {
            Text(hold.note, style = AppType.small, color = Palette.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        } else {
            Spacer(Modifier.weight(1f))
        }
        ActButton(hold.button, hold.style, icon = icon) { Dibs.tapHold(hold) }
    }
}
