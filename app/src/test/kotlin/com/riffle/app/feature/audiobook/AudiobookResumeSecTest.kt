package com.riffle.app.feature.audiobook

import org.junit.Assert.assertEquals
import org.junit.Test

class AudiobookResumeSecTest {

    @Test
    fun `keeps the reconciled position when one was found`() {
        // A real tracked/server position wins; the library-progress fallback must not override it.
        assertEquals(100.0, audiobookResumeSec(reconciledSec = 100.0, readingProgressFraction = 0.5f, durationSec = 1000.0), 1e-9)
    }

    @Test
    fun `falls back to library progress when no position was reconciled`() {
        // Offline-with-only-a-bundle: reconcile yields 0, so resume from the fraction the rest of the
        // app shows (and seed the guard floor that prevents the close-write erasing it).
        assertEquals(500.0, audiobookResumeSec(reconciledSec = 0.0, readingProgressFraction = 0.5f, durationSec = 1000.0), 1e-9)
    }

    @Test
    fun `stays at zero when there is no progress to fall back to`() {
        assertEquals(0.0, audiobookResumeSec(reconciledSec = 0.0, readingProgressFraction = 0f, durationSec = 1000.0), 1e-9)
    }

    @Test
    fun `stays at zero when the duration is unknown`() {
        assertEquals(0.0, audiobookResumeSec(reconciledSec = 0.0, readingProgressFraction = 0.5f, durationSec = 0.0), 1e-9)
    }

    @Test
    fun `clamps a full-progress fallback to the duration`() {
        assertEquals(1000.0, audiobookResumeSec(reconciledSec = 0.0, readingProgressFraction = 1f, durationSec = 1000.0), 1e-9)
    }
}
