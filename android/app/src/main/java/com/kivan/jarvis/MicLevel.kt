package com.kivan.jarvis

import kotlin.math.exp

/**
 * The call screen's halo: how well Jarvis hears Aviv, measured in the voice crate on what really
 * leaves the phone (`crates/voice/src/meter.rs`), and eased here on the frame clock for the eye.
 */
object MicLevel {
    private const val ATTACK_MS = 30f
    private const val RELEASE_MS = 250f
    /** Under this it draws as nothing, so it ends rather than fading forever. */
    private const val GONE = 0.01f

    /** The live call's meter, a lock-free read; none without a call. */
    @Volatile var source: (() -> Float)? = null

    fun now(): Float = source?.invoke() ?: 0f

    /** What to show [dtMs] after showing [shown]: up at once, down slowly, the same at any frame rate. */
    fun ease(shown: Float, target: Float, dtMs: Float): Float {
        val next = target + (shown - target) * exp(-dtMs / if (target > shown) ATTACK_MS else RELEASE_MS)
        return if (next < GONE && target < GONE) 0f else next
    }
}
