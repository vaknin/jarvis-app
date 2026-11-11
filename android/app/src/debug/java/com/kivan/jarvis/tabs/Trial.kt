package com.kivan.jarvis.tabs

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import java.io.File

/**
 * A debug build opened with `--ei tabs N` draws N made-up tabs, with no server behind them; `--ez v2 true` as Jarvis 2.0
 * sends them; `--ez fold true` as the library fold's main chat (its talk on two interleaved subjects) and threads;
 * `--ez book true` with it, his first tab a subject's (IMAGES BOOK) and the main chat a later one the server names;
 * `--ez bullets true` with it, the main chat ending on his raw lines and Jarvis's bullets; `--ez quoted true`, on his
 * quote-replies as the record keeps them (his words inside the quotes); `--ez picture true`, on a screenshot he sent
 * with no words.
 */
fun trialDeck(context: Context, intent: Intent): Deck? =
    intent.getIntExtra("tabs", -1).takeIf { it >= 0 }?.let {
        val fold = intent.getBooleanExtra("fold", false)
        var state = if (fold) (if (intent.getBooleanExtra("book", false)) Fake.foldBookFirst(it) else Fake.foldTalk(it, bullets = intent.getBooleanExtra("bullets", false), quoted = intent.getBooleanExtra("quoted", false))) else Fake.deck(it, v2 = intent.getBooleanExtra("v2", false))
        if (fold && intent.getBooleanExtra("picture", false)) state = Fake.sentPicture(state, screenshot(context).path)
        FakeDeck(state)
    }

/** A phone screenshot's shape, drawn so a picture of the screen shows it plainly: a dark page with a few light bars. */
private fun screenshot(context: Context): File {
    val file = File(context.cacheDir, "trial-screenshot.png")
    val bitmap = Bitmap.createBitmap(540, 1170, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    canvas.drawColor(0xFF1E2A3A.toInt())
    val paint = Paint().apply { color = 0xFF8FB8DE.toInt() }
    for (i in 0 until 6) canvas.drawRoundRect(40f, 120f + i * 170f, 500f - (i % 3) * 90f, 220f + i * 170f, 24f, 24f, paint)
    file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    return file
}
