package com.kivan.jarvis.tabs

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.util.Log
import com.kivan.jarvis.Core
import com.kivan.jarvis.Move
import com.kivan.jarvis.sharedUris
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The "Jarvis" entry in the share sheet, beside "Laptop". What's shared waits above the words box and
 * the wall opens, which is the picker: he taps a tab, or starts a new one, and Send takes it there.
 */
class TabShareActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Move.forward(this)) return
        val uris = sharedUris(this)
        val text = intent.getStringExtra(Intent.EXTRA_TEXT)?.takeIf { it.isNotBlank() }
        if (uris.isEmpty() && text == null) return finish()
        val deck = RealDeck.get(this)
        Core.scope.launch {
            try {
                // The read grant on the shared URIs ends with this screen: copy first, then close it.
                withContext(Dispatchers.IO) { deck.attach(uris) }
            } catch (e: Exception) {
                Log.w("Jarvis", "share into a tab failed", e)
            }
            withContext(Dispatchers.Main) {
                text?.let(deck::share)
                startActivity(TabsActivity.opening(this@TabShareActivity, WALL))
                finish()
            }
        }
    }
}
