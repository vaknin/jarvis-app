package com.kivan.jarvis.tabs

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.border

private val MODELS = listOf("haiku" to "Haiku", "sonnet" to "Sonnet", "opus" to "Opus")
private val EFFORTS = listOf("low", "medium", "high", "xhigh", "max")

/** What the chip says: "Opus medium". Empty until the tile knows its mode. */
fun modeLabel(tile: TileView): String =
    MODELS.firstOrNull { it.first == tile.model }?.let { "${it.second} ${tile.effort}" }.orEmpty()

/**
 * The model and effort a tab runs on, as a small chip; a tap opens the picker. A pick sets the tab's
 * mode at once and takes hold from the next message.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelChip(tile: TileView, onPick: (model: String, effort: String) -> Unit, modifier: Modifier = Modifier) {
    val label = modeLabel(tile)
    if (label.isEmpty()) return
    var open by remember { mutableStateOf(false) }
    Text(
        label,
        modifier
            .clip(RoundedCornerShape(50))
            .background(look.surface)
            .border(1.dp, look.line, RoundedCornerShape(50))
            .clickable(onClickLabel = "Change model and effort") { open = true }
            .padding(horizontal = 10.dp, vertical = 3.dp),
        look.soft,
        style = Words.small,
        maxLines = 1,
    )
    if (open) ModalBottomSheet({ open = false }, containerColor = look.raised) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp).padding(bottom = 16.dp)) {
            Heading("Model")
            Segments(MODELS, tile.model) { onPick(it, tile.effort) }
            Heading("Effort")
            Segments(EFFORTS.map { it to it }, tile.effort) { onPick(tile.model, it) }
            Text("From the next message.", Modifier.padding(top = 4.dp), look.faint, style = Words.small)
        }
    }
}

@Composable
private fun Heading(text: String) =
    Text(text.uppercase(), Modifier.padding(top = 4.dp, bottom = 8.dp), look.faint, style = Words.small)

@Composable
private fun Segments(items: List<Pair<String, String>>, current: String, onPick: (String) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(bottom = 14.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        for ((key, name) in items) {
            val on = key == current
            Text(
                name,
                Modifier
                    .weight(1f)
                    .heightIn(min = 44.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(if (on) look.ink else look.surface)
                    .clickable(role = Role.RadioButton) { onPick(key) }
                    .semantics { selected = on }
                    .padding(vertical = 11.dp),
                if (on) look.canvas else look.soft,
                style = Words.label,
                textAlign = TextAlign.Center,
                maxLines = 1,
            )
        }
    }
}
