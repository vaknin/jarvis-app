package com.kivan.jarvis.tabs

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

/** What the tick stands for when the server kept no words for it: the laptop's line (the jarvis repo's `tabs/tui/src/record.rs`). */
const val NO_TICK_WORDS = "No words were kept for this tick."

/** What shows under the ticks while open: the words, '…' while they're on their way, or why they couldn't come. */
fun TickWords.shown(): String = when {
    text == null && why.isNotEmpty() -> "Couldn't get them: $why"
    text == null -> "…"
    text.isBlank() -> NO_TICK_WORDS
    else -> text
}

/**
 * Jarvis's ticks under his message in tab [tab], as the laptop's Space opens them: a tap shows what they stand for
 * just above them in soft words, a second tap hides it. The words are asked of the server once ([Deck.tick]) and kept for
 * the app's run, so a tap again only shows them. Nothing with no tick, or before the message has landed.
 */
@Composable
internal fun Ticks(tab: String, entry: Entry.Yours) {
    val to = entry.offset ?: return
    if (entry.tick.isEmpty()) return
    val deck = LocalDeck.current
    val key = tickKey(tab, to)
    val words by remember(deck, key) {
        deck?.state?.map { it.ticks[key] }?.distinctUntilChanged() ?: flowOf(null)
    }.collectAsState(deck?.state?.value?.ticks?.get(key))
    val open = words?.open == true
    Column(horizontalAlignment = Alignment.End) {
        // The words open above the ticks, not under them as on the laptop: the chat lays out from the bottom, so what
        // opens grows upward, and the ticks stay under his finger for the tap that hides them (seen on the emulator:
        // opened under them, the ticks jumped up and the second tap landed on the words).
        val now = words
        if (open && now != null) {
            Text(now.shown(), Modifier.padding(top = Space.gap, end = Space.gap).testTag("tick-words"), look.soft, style = Words.small)
        }
        // The finger gets the standard target (Space.target); the ticks themselves stay small, as on the laptop.
        Box(
            Modifier
                .sizeIn(minWidth = Space.target, minHeight = Space.target)
                .clip(RoundedCornerShape(Space.gap))
                .clickable(onClickLabel = if (open) "Hide what the tick stands for" else "Show what the tick stands for") { deck?.tick(tab, to) }
                .testTag("ticks"),
            Alignment.CenterEnd,
        ) {
            Text(entry.tick, Modifier.padding(horizontal = Space.gap), if (open) look.soft else look.faint, style = Words.small)
        }
    }
}
