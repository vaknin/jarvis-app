package com.kivan.jarvis.tabs

import android.content.Intent

/** A debug build opened with `--ei tabs N` draws N made-up tabs, with no server behind them. */
fun trialDeck(intent: Intent): Deck? =
    intent.getIntExtra("tabs", -1).takeIf { it >= 0 }?.let { FakeDeck(Fake.deck(it)) }
