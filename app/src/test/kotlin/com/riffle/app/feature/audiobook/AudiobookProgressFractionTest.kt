package com.riffle.app.feature.audiobook

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The audiobook player maps the live listen position into the unified 0..1 `readingProgress` the
 * detail/library screens render. Guards the bug where audiobook progress never reached the local
 * row (only ABS), leaving the detail view stale (ADR 0029).
 */
class AudiobookProgressFractionTest {

    @Test
    fun `maps position to a fraction of duration`() {
        assertEquals(0.5f, audiobookProgressFraction(positionSec = 150.0, durationSec = 300.0), 0.0001f)
    }

    @Test
    fun `unknown duration yields zero so a not-yet-prepared player never writes a bogus full`() {
        assertEquals(0f, audiobookProgressFraction(positionSec = 42.0, durationSec = 0.0), 0f)
    }

    @Test
    fun `clamps an overshooting position to one`() {
        assertEquals(1f, audiobookProgressFraction(positionSec = 305.0, durationSec = 300.0), 0f)
    }

    @Test
    fun `start of book is zero`() {
        assertEquals(0f, audiobookProgressFraction(positionSec = 0.0, durationSec = 300.0), 0f)
    }
}
