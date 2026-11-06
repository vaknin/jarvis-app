package com.kivan.jarvis.tabs

import android.content.Intent

/** A debug build opened with `--ei tabs N` draws N made-up tabs, with no server behind them; `--ez v2 true` as Jarvis 2.0 sends them. */
fun trialDeck(intent: Intent): Deck? =
    intent.getIntExtra("tabs", -1).takeIf { it >= 0 }?.let { FakeDeck(Fake.deck(it, v2 = intent.getBooleanExtra("v2", false))) }
