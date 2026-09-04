package com.riffle.feature.player

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AudiobookProgressUtilsTest {

    // ── audiobookResumeSec ──────────────────────────────────────────────────────

    @Test
    fun `audiobookResumeSec returns reconciledSec when hadTrackedPosition is true`() {
        val result = audiobookResumeSec(
            reconciledSec = 100.0,
            hadTrackedPosition = true,
            readingProgressFraction = 0.5f,
            durationSec = 3600.0,
        )
        assertEquals(100.0, result)
    }

    @Test
    fun `audiobookResumeSec uses fraction fallback when no tracked position`() {
        val result = audiobookResumeSec(
            reconciledSec = 0.0,
            hadTrackedPosition = false,
            readingProgressFraction = 0.5f,
            durationSec = 3600.0,
        )
        assertEquals(1800.0, result, absoluteTolerance = 0.001)
    }

    @Test
    fun `audiobookResumeSec returns reconciledSec when fraction is zero`() {
        val result = audiobookResumeSec(
            reconciledSec = 50.0,
            hadTrackedPosition = false,
            readingProgressFraction = 0.0f,
            durationSec = 3600.0,
        )
        assertEquals(50.0, result)
    }

    @Test
    fun `audiobookResumeSec returns reconciledSec when duration is zero`() {
        val result = audiobookResumeSec(
            reconciledSec = 50.0,
            hadTrackedPosition = false,
            readingProgressFraction = 0.5f,
            durationSec = 0.0,
        )
        assertEquals(50.0, result)
    }

    // ── audiobookStartSec ───────────────────────────────────────────────────────

    @Test
    fun `audiobookStartSec returns 0 when resume is at end of book`() {
        val durationSec = 3600.0
        // Within AUDIOBOOK_FINISHED_EPS_SEC of the end
        val resumeSec = durationSec - 0.5
        val result = audiobookStartSec(resumeSec, durationSec)
        assertEquals(0.0, result)
    }

    @Test
    fun `audiobookStartSec returns resumeSec when not near end`() {
        val durationSec = 3600.0
        val resumeSec = 100.0
        val result = audiobookStartSec(resumeSec, durationSec)
        assertEquals(100.0, result)
    }

    @Test
    fun `audiobookStartSec returns resumeSec when duration unknown`() {
        val result = audiobookStartSec(100.0, 0.0)
        assertEquals(100.0, result)
    }

    // ── audiobookProgressFraction ───────────────────────────────────────────────

    @Test
    fun `audiobookProgressFraction returns 0 when duration is zero`() {
        assertEquals(0f, audiobookProgressFraction(100.0, 0.0))
    }

    @Test
    fun `audiobookProgressFraction returns 1 near end`() {
        val durationSec = 3600.0
        val positionSec = durationSec - 0.5
        assertEquals(1f, audiobookProgressFraction(positionSec, durationSec))
    }

    @Test
    fun `audiobookProgressFraction returns correct fraction mid-book`() {
        val result = audiobookProgressFraction(1800.0, 3600.0)
        assertEquals(0.5f, result, absoluteTolerance = 0.001f)
    }

    // ── formatCompactDuration ───────────────────────────────────────────────────

    @Test
    fun `formatCompactDuration renders minutes only`() {
        val result = formatCompactDuration(300.0) // 5 minutes
        assertEquals("5m", result)
    }

    @Test
    fun `formatCompactDuration renders hours only`() {
        val result = formatCompactDuration(7200.0) // 2 hours, 0 minutes
        assertEquals("2h", result)
    }

    @Test
    fun `formatCompactDuration renders hours and minutes`() {
        val result = formatCompactDuration(7500.0) // 2h 5m
        assertEquals("2h 5m", result)
    }

    // ── buildAudiobookFacts ─────────────────────────────────────────────────────

    @Test
    fun `buildAudiobookFacts returns null when only label`() {
        val result = buildAudiobookFacts(0.0, emptyList())
        assertEquals(null, result)
    }

    @Test
    fun `buildAudiobookFacts includes duration when positive`() {
        val result = buildAudiobookFacts(3600.0, emptyList())
        assertTrue(result?.contains("1h") == true)
    }

    @Test
    fun `buildAudiobookFacts includes up to two genres`() {
        val result = buildAudiobookFacts(3600.0, listOf("Mystery", "Thriller", "Crime"))
        assertTrue(result?.contains("Mystery") == true)
        assertTrue(result?.contains("Thriller") == true)
        assertFalse(result?.contains("Crime") == true)
    }

    // ── readaloudControlState ───────────────────────────────────────────────────

    @Test
    fun `readaloudControlState is enabled for Storyteller`() {
        val state = readaloudControlState(isStoryteller = true, isMatchedAbs = false, bundlePresent = false)
        assertTrue(state.visible)
        assertTrue(state.enabled)
    }

    @Test
    fun `readaloudControlState is enabled for matched ABS`() {
        val state = readaloudControlState(isStoryteller = false, isMatchedAbs = true, bundlePresent = false)
        assertTrue(state.visible)
        assertTrue(state.enabled)
    }

    @Test
    fun `readaloudControlState is hidden for unmatched ABS`() {
        val state = readaloudControlState(isStoryteller = false, isMatchedAbs = false, bundlePresent = true)
        assertFalse(state.visible)
        assertFalse(state.enabled)
    }
}

private fun assertEquals(expected: Double, actual: Double, absoluteTolerance: Double) {
    assertTrue(
        kotlin.math.abs(expected - actual) <= absoluteTolerance,
        "Expected $expected but was $actual (tolerance $absoluteTolerance)"
    )
}

private fun assertEquals(expected: Float, actual: Float, absoluteTolerance: Float) {
    assertTrue(
        kotlin.math.abs(expected - actual) <= absoluteTolerance,
        "Expected $expected but was $actual (tolerance $absoluteTolerance)"
    )
}
