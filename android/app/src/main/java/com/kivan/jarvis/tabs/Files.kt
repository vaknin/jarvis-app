package com.kivan.jarvis.tabs

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.webkit.MimeTypeMap
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.kivan.jarvis.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.io.File

/** The folder under the app's cache that the phone hands files to other apps from (file_paths.xml). */
const val TAB_FILES = "tabfiles"

/** The deck, for what's far from it: a card in a tab's record. */
val LocalDeck = staticCompositionLocalOf<Deck?> { null }

private val PICTURES = setOf("png", "jpg", "jpeg", "webp", "gif", "heic")

/**
 * A file beside the record. A picture comes by itself and shows whole, never cropped; anything else
 * comes on a tap and opens in what the phone has for it.
 */
@Composable
fun FileCard(tab: String, name: String, modifier: Modifier = Modifier) {
    val deck = LocalDeck.current
    val key = "$tab/$name"
    val path by remember(deck, key) {
        deck?.state?.map { it.files[key] }?.distinctUntilChanged() ?: kotlinx.coroutines.flow.flowOf(null)
    }.collectAsState(deck?.state?.value?.files?.get(key))
    val context = LocalContext.current
    val picture = name.substringAfterLast('.').lowercase() in PICTURES
    var wanted by remember { mutableStateOf(false) }
    LaunchedEffect(key) { if (picture && path == null) deck?.file(tab, name) }
    LaunchedEffect(path) {
        val got = path
        if (wanted && got != null) {
            wanted = false
            if (got.isNotEmpty()) open(context, File(got))
        }
    }
    val bitmap by produceState<ImageBitmap?>(null, path) {
        value = path?.takeIf { picture && it.isNotEmpty() }?.let { withContext(Dispatchers.IO) { smallBitmap(it) } }
    }
    val tap = {
        val got = path
        if (got.isNullOrEmpty()) {
            wanted = true
            deck?.file(tab, name)
        } else {
            open(context, File(got))
        }
        Unit
    }

    val shape = RoundedCornerShape(14.dp)
    val shown = bitmap
    if (shown != null) {
        val ratio = shown.width.toFloat() / shown.height
        Image(
            shown,
            name,
            modifier
                .sizeIn(maxWidth = 300.dp, maxHeight = 360.dp)
                .aspectRatio(ratio, matchHeightConstraintsFirst = ratio < 1)
                .clip(shape)
                .clickable(onClickLabel = "Open the picture", role = Role.Image, onClick = tap),
            contentScale = ContentScale.Fit,
        )
        return
    }
    val line = when {
        path?.isEmpty() == true -> "Not on the server any more"
        wanted -> "Fetching"
        else -> null
    }
    Row(
        modifier
            .clip(shape)
            .background(look.surface)
            .clickable(onClickLabel = "Open the file", role = Role.Button, onClick = tap)
            .heightIn(min = Space.target)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Glyph(R.drawable.lucide_file, look.soft, 18.dp)
        Column {
            Text(name, color = look.ink, style = Words.small, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (line != null) Text(line, color = look.faint, style = Words.small)
        }
    }
}

/** Big enough for a phone's width, small enough that a photo can't take the memory. */
private fun smallBitmap(path: String): ImageBitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(path, bounds)
    var sample = 1
    while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= LONGEST) sample *= 2
    return BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample })?.asImageBitmap()
}

private const val LONGEST = 1200

private fun open(context: Context, file: File) {
    val uri = FileProvider.getUriForFile(context, context.packageName + ".files", file)
    val type = MimeTypeMap.getSingleton().getMimeTypeFromExtension(file.extension.lowercase()) ?: "application/octet-stream"
    val view = Intent(Intent.ACTION_VIEW).setDataAndType(uri, type).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    try {
        context.startActivity(view)
    } catch (_: ActivityNotFoundException) {
        Toast.makeText(context, "Nothing on this phone opens ${file.name}", Toast.LENGTH_SHORT).show()
    }
}
