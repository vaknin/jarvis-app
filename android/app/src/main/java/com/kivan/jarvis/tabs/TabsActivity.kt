package com.kivan.jarvis.tabs

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge

/** The wall and its tabs, beside today's home. */
class TabsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val deck = (lastNonConfigurationInstance as? Deck) ?: FakeDeck(Fake.deck(intent.getIntExtra("tabs", 9)))
        held = deck
        setContent {
            TabsTheme { Stage(deck) }
        }
    }

    private var held: Deck? = null

    @Deprecated("A Deck outlives a rotation")
    override fun onRetainCustomNonConfigurationInstance(): Any? = held
}
