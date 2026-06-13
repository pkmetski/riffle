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

    @Test
    fun `restarts a finished book (resume at the end) from zero`() {
        // The bug: a resume exactly at the duration seeds the player at the end of the last track, where
        // ExoPlayer is STATE_ENDED and play() is a no-op — silent, bar pinned at the end. Replay from 0.
        assertEquals(0.0, audiobookStartSec(resumeSec = 1000.0, durationSec = 1000.0), 1e-9)
    }

    @Test
    fun `restarts when within the finished epsilon of the end`() {
        // Rounding can leave the stored position a hair short of the duration; still unplayable, so reset.
        assertEquals(
            0.0,
            audiobookStartSec(resumeSec = 1000.0 - AUDIOBOOK_FINISHED_EPS_SEC / 2, durationSec = 1000.0),
            1e-9,
        )
    }

    @Test
    fun `honours a mid-book resume`() {
        assertEquals(500.0, audiobookStartSec(resumeSec = 500.0, durationSec = 1000.0), 1e-9)
    }

    @Test
    fun `keeps a resume just before the finished epsilon`() {
        // A position more than the epsilon short of the end is a genuine mid-listen point — keep it.
        assertEquals(990.0, audiobookStartSec(resumeSec = 990.0, durationSec = 1000.0), 1e-9)
    }

    @Test
    fun `leaves the position alone when the duration is unknown`() {
        // Can't tell where "the end" is, so never reset (don't restart a resume we can't classify).
        assertEquals(42.0, audiobookStartSec(resumeSec = 42.0, durationSec = 0.0), 1e-9)
    }
}
