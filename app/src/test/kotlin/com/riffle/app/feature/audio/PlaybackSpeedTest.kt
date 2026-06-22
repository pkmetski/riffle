package com.riffle.app.feature.audio

import org.junit.Assert.assertEquals
import org.junit.Test

class PlaybackSpeedTest {

    // ── snap ─────────────────────────────────────────────────────────────────

    @Test fun snap_clampsAtMin() {
        assertEquals(PlaybackSpeed.MIN, PlaybackSpeed.snap(PlaybackSpeed.MIN - PlaybackSpeed.STEP), 0.001f)
    }

    @Test fun snap_clampsAtMax() {
        assertEquals(PlaybackSpeed.MAX, PlaybackSpeed.snap(PlaybackSpeed.MAX + PlaybackSpeed.STEP), 0.001f)
    }

    @Test fun snap_returnsExactStepValue() {
        assertEquals(1.25f, PlaybackSpeed.snap(1.25f), 0.001f)
    }

    @Test fun snap_roundsToNearestStep() {
        // 1.27 is closer to 1.25 than 1.30
        assertEquals(1.25f, PlaybackSpeed.snap(1.27f), 0.001f)
        // 1.28 is closer to 1.30
        assertEquals(1.30f, PlaybackSpeed.snap(1.28f), 0.001f)
    }

    @Test fun snap_handlesFloatDriftFromRepeatedNudges() {
        // Simulate 5 nudges up from 1.0 — floating-point addition can drift.
        var v = 1.0f
        repeat(5) { v = PlaybackSpeed.snap(v + PlaybackSpeed.STEP) }
        assertEquals(1.25f, v, 0.001f)
    }

    @Test fun snap_nudgeBelowMinClampsToMin() {
        assertEquals(PlaybackSpeed.MIN, PlaybackSpeed.snap(0.0f), 0.001f)
    }

    // ── label ─────────────────────────────────────────────────────────────────

    @Test fun label_wholeNumberOmitsDecimal() {
        assertEquals("1×", PlaybackSpeed.label(1.0f))
        assertEquals("2×", PlaybackSpeed.label(2.0f))
        assertEquals("3×", PlaybackSpeed.label(3.0f))
    }

    @Test fun label_trimsTrailingZeros() {
        assertEquals("1.25×", PlaybackSpeed.label(1.25f))
        assertEquals("0.75×", PlaybackSpeed.label(0.75f))
        assertEquals("1.5×", PlaybackSpeed.label(1.5f))
    }

    @Test fun label_handlesMin() {
        assertEquals("0.5×", PlaybackSpeed.label(PlaybackSpeed.MIN))
    }

    @Test fun label_handlesDriftedFloat() {
        // 1.35000002 (common float drift) should print as "1.35×"
        assertEquals("1.35×", PlaybackSpeed.label(1.3500001f))
    }
}
