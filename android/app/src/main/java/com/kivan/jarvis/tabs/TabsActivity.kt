package com.kivan.jarvis.tabs

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge

/** The wall of tabs. The link to the server lives only while this is on screen. */
class TabsActivity : ComponentActivity() {
    private lateinit var deck: Deck

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        deck = (lastNonConfigurationInstance as? Deck) ?: trialDeck(intent) ?: RealDeck.get(this)
        setContent { TabsTheme { Stage(deck) } }
    }

    override fun onStart() {
        super.onStart()
        (deck as? RealDeck)?.up()
    }

    override fun onStop() {
        super.onStop()
        if (!isChangingConfigurations) (deck as? RealDeck)?.down()
    }

    @Deprecated("A Deck outlives a rotation")
    override fun onRetainCustomNonConfigurationInstance(): Any = deck
}
