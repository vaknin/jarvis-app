package com.kivan.jarvis.tabs

import android.content.Intent

/**
 * A debug build opened with `--ei tabs N` draws N made-up tabs, with no server behind them; `--ez v2 true` as Jarvis 2.0
 * sends them; `--ez fold true` as the library fold's main chat (its talk on two interleaved subjects) and threads;
 * `--ez book true` with it, his first tab a subject's (IMAGES BOOK) and the main chat a later one the server names;
 * `--ez bullets true` with it, the main chat ending on his raw lines and Jarvis's bullets; `--ez quoted true`, on his
 * quote-replies as the record keeps them (his words inside the quotes).
 */
fun trialDeck(intent: Intent): Deck? =
    intent.getIntExtra("tabs", -1).takeIf { it >= 0 }?.let {
        FakeDeck(if (intent.getBooleanExtra("fold", false)) (if (intent.getBooleanExtra("book", false)) Fake.foldBookFirst(it) else Fake.foldTalk(it, bullets = intent.getBooleanExtra("bullets", false), quoted = intent.getBooleanExtra("quoted", false))) else Fake.deck(it, v2 = intent.getBooleanExtra("v2", false)))
    }
