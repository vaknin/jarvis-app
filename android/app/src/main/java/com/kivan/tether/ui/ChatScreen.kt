package com.kivan.tether.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.ImageDecoder
import android.net.Uri
import android.text.format.DateFormat
import android.text.format.Formatter
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import com.kivan.tether.CallActivity
import com.kivan.tether.Core
import com.kivan.tether.Downloads
import com.kivan.tether.BuildConfig
import com.kivan.tether.Forum
import com.kivan.tether.Updater
import com.kivan.tether.Outgoing
import com.kivan.tether.R
import com.kivan.tether.core.ChatMessage
import com.kivan.tether.core.MsgKind
import com.kivan.tether.core.MsgState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Date
import kotlin.math.max
import kotlin.math.min

private val Bg = Color(0xFF0F1114)
private val Accent = Color(0xFF95C9FF)
private val Mine = Color(0xFF243447)
private val Theirs = Color(0xFF1C1F24)
private val Muted = Color(0xFF8B93A1)

@Composable
fun ChatScreen() {
    MaterialTheme(colorScheme = darkColorScheme(primary = Accent, background = Bg, surface = Bg)) {
        val status by Core.status.collectAsState()
        Surface(Modifier.fillMaxSize(), color = Bg) {
            val s = status
            when {
                s == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                s.peer == null -> PairScreen()
                else -> Chat(s.peer!!.name, s.queued)
            }
        }
    }
}

@Composable
private fun PairScreen() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var code by rememberSaveable { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    fun pair(c: String) {
        busy = true
        error = null
        scope.launch {
            try {
                Core.pair(c)
            } catch (e: Exception) {
                error = e.message ?: "Pairing failed"
            } finally {
                busy = false
            }
        }
    }

    Column(
        Modifier.fillMaxSize().safeDrawingPadding().imePadding().padding(horizontal = 28.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(painterResource(R.drawable.lucide_laptop), null, Modifier.size(72.dp), tint = Accent)
        Text("Link your laptop", style = MaterialTheme.typography.headlineSmall)
        Text(
            "Start pairing on the laptop and scan the QR code it shows. The code works once, for 5 minutes.",
            color = Muted,
            textAlign = TextAlign.Center,
        )
        Button(
            enabled = !busy,
            modifier = Modifier.fillMaxWidth().height(52.dp),
            onClick = {
                val options = GmsBarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).build()
                GmsBarcodeScanning.getClient(ctx, options).startScan()
                    .addOnSuccessListener { b -> b.rawValue?.let(::pair) }
                    .addOnFailureListener { error = it.message }
            },
        ) {
            Icon(painterResource(R.drawable.lucide_qr_code), null, Modifier.size(20.dp))
            Spacer(Modifier.width(10.dp))
            Text("Scan QR code")
        }
        OutlinedTextField(
            value = code,
            onValueChange = { code = it },
            singleLine = true,
            placeholder = { Text("or paste the code") },
            modifier = Modifier.fillMaxWidth(),
        )
        TextButton(enabled = !busy && code.isNotBlank(), onClick = { pair(code) }) { Text("Pair") }
        if (busy) CircularProgressIndicator()
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center) }
    }
}

@Composable
private fun Chat(peer: String, queued: ULong) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val messages by Core.messages.collectAsState()
    val connected by Core.connected.collectAsState()
    val refused by Core.refused.collectAsState()
    val progress by Core.progress.collectAsState()
    val update by Updater.state.collectAsState()
    var text by rememberSaveable { mutableStateOf("") }
    var menu by remember { mutableStateOf(false) }
    val chat = remember(messages) { messages.filter { it.channel == null }.reversed() }

    val pick = rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        Core.scope.launch {
            runCatching {
                val files = withContext(Dispatchers.IO) { uris.map { Outgoing.copyIn(ctx, it) } }
                files.forEach { Core.sendFile(it) }
            }.onFailure { e -> withContext(Dispatchers.Main) { toast(ctx, "Couldn't send: ${e.message}") } }
        }
    }

    Column(Modifier.fillMaxSize().safeDrawingPadding().imePadding()) {
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(painterResource(R.drawable.lucide_laptop), null, Modifier.size(28.dp), tint = Accent)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(peer, style = MaterialTheme.typography.titleMedium)
                val line = when {
                    refused -> "The laptop paired another phone"
                    connected -> "Linked"
                    queued > 0uL -> "$queued waiting to send"
                    else -> "Delivers when the laptop is reachable"
                }
                Text(line, style = MaterialTheme.typography.bodySmall, color = if (refused) MaterialTheme.colorScheme.error else Muted)
            }
            IconButton(onClick = { ctx.startActivity(CallActivity.intent(ctx, start = false)) }) {
                Icon(painterResource(R.drawable.lucide_phone), "Call Jarvis", tint = Accent)
            }
            TextButton(onClick = { Forum.open(ctx) }) { Text("Forum") }
            Box {
                IconButton(onClick = { menu = true }) { Icon(painterResource(R.drawable.lucide_ellipsis_vertical), "More") }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text("Forum tunnel: ${Forum.status()}") }, onClick = { menu = false })
                    DropdownMenuItem(
                        text = { Text(update.ifEmpty { "Check for update (${BuildConfig.VERSION_NAME})" }) },
                        onClick = { menu = false; Updater.checkNow(ctx) },
                    )
                    DropdownMenuItem(text = { Text("Unpair") }, onClick = {
                        menu = false
                        scope.launch { Core.unpair() }
                    })
                }
            }
        }
        LazyColumn(
            Modifier.weight(1f).fillMaxWidth(),
            reverseLayout = true,
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            items(chat, key = { it.id }) { m -> Bubble(m, progress[m.id]) }
        }
        Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.Bottom) {
            IconButton(onClick = { pick.launch("*/*") }) { Icon(painterResource(R.drawable.lucide_paperclip), "Attach") }
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                modifier = Modifier.weight(1f).heightIn(max = 160.dp),
                placeholder = { Text("Message") },
                shape = RoundedCornerShape(22.dp),
            )
            IconButton(
                enabled = text.isNotBlank(),
                onClick = {
                    val t = text.trim()
                    text = ""
                    Core.scope.launch {
                        runCatching { Core.sendText(t) }
                            .onFailure { e -> withContext(Dispatchers.Main) { toast(ctx, "Couldn't send: ${e.message}") } }
                    }
                },
            ) { Icon(painterResource(R.drawable.lucide_send_horizontal), "Send", tint = if (text.isNotBlank()) Accent else Muted) }
        }
    }
}

@Composable
private fun Bubble(m: ChatMessage, progress: Pair<Long, Long>?) {
    val ctx = LocalContext.current
    val mine = m.fromMe
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start) {
        Column(
            Modifier.widthIn(max = 300.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(if (mine) Mine else Theirs)
                .combinedClickable(onClick = { open(ctx, m) }, onLongClick = { copy(ctx, m) })
                .padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            when (m.kind) {
                MsgKind.FILE -> FileBody(m, progress)
                MsgKind.RING -> Text("Ring", color = Muted)
                else -> Text(m.text ?: "")
            }
            Row(Modifier.align(Alignment.End), verticalAlignment = Alignment.CenterVertically) {
                Text(DateFormat.getTimeFormat(ctx).format(Date(m.tsMs)), style = MaterialTheme.typography.labelSmall, color = Muted)
                if (mine) {
                    Spacer(Modifier.width(4.dp))
                    val icon = when (m.state) {
                        MsgState.DELIVERED -> R.drawable.lucide_check_check
                        MsgState.QUEUED -> R.drawable.lucide_clock
                        MsgState.EXPIRED, MsgState.CANCELLED -> R.drawable.lucide_circle_alert
                        else -> R.drawable.lucide_check
                    }
                    Icon(painterResource(icon), m.state.name.lowercase(), Modifier.size(14.dp), tint = Muted)
                }
            }
        }
    }
}

@Composable
private fun FileBody(m: ChatMessage, progress: Pair<Long, Long>?) {
    val ctx = LocalContext.current
    val name = m.fileName ?: "file"
    val image = Downloads.mimeOf(name).startsWith("image/")
    if (image) {
        val bitmap by produceState<ImageBitmap?>(null, m.id, m.state) {
            value = withContext(Dispatchers.IO) { thumbnail(ctx, m) }
        }
        bitmap?.let {
            Image(it, name, Modifier.widthIn(max = 276.dp).heightIn(max = 360.dp).clip(RoundedCornerShape(10.dp)), contentScale = ContentScale.Fit)
            Spacer(Modifier.height(4.dp))
        }
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(painterResource(R.drawable.lucide_file), null, Modifier.size(18.dp), tint = Accent)
        Spacer(Modifier.width(8.dp))
        Column {
            Text(name, maxLines = 2)
            m.fileSize?.let { Text(Formatter.formatShortFileSize(ctx, it.toLong()), style = MaterialTheme.typography.labelSmall, color = Muted) }
        }
    }
    progress?.let { (done, total) ->
        Spacer(Modifier.height(6.dp))
        LinearProgressIndicator(progress = { if (total > 0) done.toFloat() / total else 0f }, Modifier.width(200.dp).clip(CircleShape))
    }
}

/** The file to show for [m]: its copy in Downloads (received) or the queued copy (sent, until delivered). */
private fun source(ctx: Context, m: ChatMessage): Uri? =
    if (m.fromMe) m.path?.let { java.io.File(it) }?.takeIf { it.isFile }?.let(Uri::fromFile)
    else Downloads.uriFor(ctx, m.id)

private fun thumbnail(ctx: Context, m: ChatMessage): ImageBitmap? = runCatching {
    val uri = source(ctx, m) ?: return null
    val src = if (uri.scheme == "file") ImageDecoder.createSource(java.io.File(uri.path!!))
    else ImageDecoder.createSource(ctx.contentResolver, uri)
    ImageDecoder.decodeBitmap(src) { d, info, _ ->
        val scale = min(1f, 720f / max(info.size.width, info.size.height))
        d.setTargetSize(max(1, (info.size.width * scale).toInt()), max(1, (info.size.height * scale).toInt()))
    }.asImageBitmap()
}.getOrNull()

private fun open(ctx: Context, m: ChatMessage) {
    if (m.kind != MsgKind.FILE || m.fromMe) return
    val uri = Downloads.uriFor(ctx, m.id) ?: return toast(ctx, "Not saved yet")
    val view = Intent(Intent.ACTION_VIEW).setDataAndType(uri, Downloads.mimeOf(m.fileName ?: ""))
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    runCatching { ctx.startActivity(view) }.onFailure { toast(ctx, "No app opens this file") }
}

private fun copy(ctx: Context, m: ChatMessage) {
    val t = m.text ?: m.fileName ?: return
    ctx.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Jarvis", t))
    toast(ctx, "Copied")
}

private fun toast(ctx: Context, msg: String) = Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show()
