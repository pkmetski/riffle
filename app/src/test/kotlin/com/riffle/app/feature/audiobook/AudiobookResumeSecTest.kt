package com.riffle.app.feature.audiobook

import org.junit.Assert.assertEquals
import org.junit.Test

class AudiobookResumeSecTest {

    @Test
    fun `keeps the reconciled position when one was tracked`() {
        // A real tracked/server position wins; the library-progress fallback must not override it.
        assertEquals(
            100.0,
            audiobookResumeSec(reconciledSec = 100.0, hadTrackedPosition = true, readingProgressFraction = 0.5f, durationSec = 1000.0),
            1e-9,
        )
    }

    @Test
    fun `honors a tracked position of exactly zero (does not fall back)`() {
        // A genuine "at the start" record (e.g. server reset, with a real timestamp) must be kept, not
        // replaced by the progress fallback — the falsy-zero trap.
        assertEquals(
            0.0,
            audiobookResumeSec(reconciledSec = 0.0, hadTrackedPosition = true, readingProgressFraction = 0.5f, durationSec = 1000.0),
            1e-9,
        )
    }

    @Test
    fun `falls back to library progress when no position was tracked`() {
        // Offline-with-only-a-bundle: nothing tracked, so resume from the fraction the rest of the app
        // shows (and seed the guard floor that prevents the close-write erasing it).
        assertEquals(
            500.0,
            audiobookResumeSec(reconciledSec = 0.0, hadTrackedPosition = false, readingProgressFraction = 0.5f, durationSec = 1000.0),
            1e-9,
        )
    }

    @Test
    fun `stays at zero when there is no progress to fall back to`() {
        assertEquals(
            0.0,
            audiobookResumeSec(reconciledSec = 0.0, hadTrackedPosition = false, readingProgressFraction = 0f, durationSec = 1000.0),
            1e-9,
        )
    }

    @Test
    fun `stays at zero when the duration is unknown`() {
        assertEquals(
            0.0,
            audiobookResumeSec(reconciledSec = 0.0, hadTrackedPosition = false, readingProgressFraction = 0.5f, durationSec = 0.0),
            1e-9,
        )
    }

    @Test
    fun `clamps a full-progress fallback to the duration`() {
        assertEquals(
            1000.0,
            audiobookResumeSec(reconciledSec = 0.0, hadTrackedPosition = false, readingProgressFraction = 1f, durationSec = 1000.0),
            1e-9,
        )
    }
}
