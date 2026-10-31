package com.kivan.jarvis.tabs

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.kivan.jarvis.R

/** Closed tabs, found by name. The box is at the bottom, where the thumb already is. */
@Composable
fun ClosedPage(
    closed: List<ClosedTab>,
    muted: Boolean,
    onOpen: (ClosedTab) -> Unit,
    onBack: () -> Unit,
    onMute: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    var query by remember { mutableStateOf("") }
    val found = remember(closed, query) {
        val q = query.trim()
        if (q.isEmpty()) closed else closed.filter { it.name.contains(q, ignoreCase = true) }
    }
    val focus = remember { FocusRequester() }
    Column(modifier.fillMaxSize().background(look.canvas).windowInsetsPadding(WindowInsets.statusBars)) {
        Row(
            Modifier.fillMaxWidth().height(56.dp).padding(start = 4.dp, end = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Round(R.drawable.lucide_arrow_left, "Back to the wall", onBack)
            Text("Closed tabs", Modifier.padding(start = 4.dp), look.ink, style = Words.title)
        }
        Box(Modifier.weight(1f).fillMaxWidth(), Alignment.BottomCenter) {
            if (found.isEmpty()) {
                Box(Modifier.fillMaxSize(), Alignment.Center) {
                    Text(
                        if (closed.isEmpty()) "No closed tabs yet" else "Nothing closed by that name",
                        color = look.faint,
                        style = Words.label,
                    )
                }
            } else {
                LazyColumn(
                    Modifier.reading().fillMaxSize(),
                    reverseLayout = true,
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
                ) {
                    items(found, key = { it.id }) { tab -> ClosedRow(tab, query.trim()) { onOpen(tab) } }
                }
            }
        }
        Dock("Search by name", query, { query = it }, {}, muted, onMute, focus = focus, showSend = false)
    }
}

@Composable
private fun ClosedRow(tab: ClosedTab, query: String, onClick: () -> Unit) {
    val ink = look.ink
    val name = remember(tab.name, query, ink) {
        buildAnnotatedString {
            val at = if (query.isEmpty()) -1 else tab.name.indexOf(query, ignoreCase = true)
            if (at < 0) {
                append(tab.name)
            } else {
                append(tab.name.substring(0, at))
                withStyle(SpanStyle(fontWeight = FontWeight.W600, color = ink)) { append(tab.name.substring(at, at + query.length)) }
                append(tab.name.substring(at + query.length))
            }
        }
    }
    Column(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .heightIn(min = 60.dp)
            .padding(horizontal = 10.dp, vertical = 10.dp),
    ) {
        Text(name, color = look.ink, style = Words.label, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (tab.line.isNotEmpty()) {
            Text(tab.line, Modifier.padding(top = 2.dp), look.faint, style = Words.small, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}
