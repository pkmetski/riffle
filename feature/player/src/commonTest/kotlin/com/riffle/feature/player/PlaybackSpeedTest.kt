package com.riffle.feature.player

import kotlin.test.Test
import kotlin.test.assertEquals

class PlaybackSpeedTest {

    // ── snap ──────────────────────────────────────────────────────────────────

    @Test
    fun snapClampsAtMin() {
        assertEquals(PlaybackSpeed.MIN, PlaybackSpeed.snap(PlaybackSpeed.MIN - PlaybackSpeed.STEP), 0.001f)
    }

    @Test
    fun snapClampsAtMax() {
        assertEquals(PlaybackSpeed.MAX, PlaybackSpeed.snap(PlaybackSpeed.MAX + PlaybackSpeed.STEP), 0.001f)
    }

    @Test
    fun snapReturnsExactStepValue() {
        assertEquals(1.25f, PlaybackSpeed.snap(1.25f), 0.001f)
    }

    @Test
    fun snapRoundsToNearestStep() {
        assertEquals(1.25f, PlaybackSpeed.snap(1.27f), 0.001f)
        assertEquals(1.30f, PlaybackSpeed.snap(1.28f), 0.001f)
    }

    @Test
    fun snapHandlesFloatDriftFromRepeatedNudges() {
        var v = 1.0f
        repeat(5) { v = PlaybackSpeed.snap(v + PlaybackSpeed.STEP) }
        assertEquals(1.25f, v, 0.001f)
    }

    @Test
    fun snapNudgeBelowMinClampsToMin() {
        assertEquals(PlaybackSpeed.MIN, PlaybackSpeed.snap(0.0f), 0.001f)
    }

    // ── label ─────────────────────────────────────────────────────────────────

    @Test
    fun labelWholeNumberOmitsDecimal() {
        assertEquals("1×", PlaybackSpeed.label(1.0f))
        assertEquals("2×", PlaybackSpeed.label(2.0f))
        assertEquals("3×", PlaybackSpeed.label(3.0f))
    }

    @Test
    fun labelTrimsTrailingZeros() {
        assertEquals("1.25×", PlaybackSpeed.label(1.25f))
        assertEquals("0.75×", PlaybackSpeed.label(0.75f))
        assertEquals("1.5×", PlaybackSpeed.label(1.5f))
    }

    @Test
    fun labelHandlesMin() {
        assertEquals("0.5×", PlaybackSpeed.label(PlaybackSpeed.MIN))
    }

    @Test
    fun labelHandlesDriftedFloat() {
        assertEquals("1.35×", PlaybackSpeed.label(1.3500001f))
    }
}
