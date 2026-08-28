package com.riffle.app.feature.audiobook

/**
 * Maps a book-absolute listen position to the unified 0..1 `readingProgress` fraction the library
 * and detail screens render (ADR 0035). Returns 0 when the duration isn't known yet (so a not-yet-
 * prepared player never writes a bogus 100%). Snaps to 1f within [AUDIOBOOK_FINISHED_EPS_SEC] of
 * the end so a book listened all the way through displays 100%, not 99% — LAME encoder-delay/
 * padding samples and the silent Xing/Info frame leave the reported position a hair short of the
 * Xing-derived total, and integer truncation at the display sites would otherwise render "99%".
 */
fun audiobookProgressFraction(positionSec: Double, durationSec: Double): Float =
    when {
        durationSec <= 0.0 -> 0f
        positionSec >= durationSec - AUDIOBOOK_FINISHED_EPS_SEC -> 1f
        else -> (positionSec / durationSec).toFloat().coerceIn(0f, 1f)
    }
