package com.riffle.app.feature.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UnifiedSliderLabelsTest {

    @Test fun fontSizeBubbleRoundsToPercent() {
        assertEquals("50%", fontSizeBubble(0.5f))
        assertEquals("100%", fontSizeBubble(1.0f))
        assertEquals("120%", fontSizeBubble(1.2f))
        assertEquals("250%", fontSizeBubble(2.5f))
    }

    @Test fun lineSpacingBubbleFormatsOneDecimal() {
        assertEquals("1.0×", lineSpacingBubble(1.0f))
        assertEquals("1.4×", lineSpacingBubble(1.4f))
        assertEquals("2.0×", lineSpacingBubble(2.0f))
    }

    @Test fun marginsBubbleFormatsOneDecimal() {
        assertEquals("0.2×", marginsBubble(0.2f))
        assertEquals("1.0×", marginsBubble(1.0f))
        assertEquals("3.0×", marginsBubble(3.0f))
    }

    @Test fun wpmBubbleShowsInteger() {
        assertEquals("80", wpmBubble(80f))
        assertEquals("250", wpmBubble(250.4f))
        assertEquals("600", wpmBubble(600f))
    }

    @Test fun isMajorTickTrueAtIntegerMultiples() {
        // WPM majors every 100 → 100, 200, 300, …
        assertTrue(isMajorTick(value = 100f, majorEvery = 100f))
        assertTrue(isMajorTick(value = 500f, majorEvery = 100f))
        // Font majors every 0.5 → 0.5, 1.0, 1.5, 2.0, 2.5
        assertTrue(isMajorTick(value = 1.0f, majorEvery = 0.5f))
        assertTrue(isMajorTick(value = 2.5f, majorEvery = 0.5f))
        // Spacing majors every 0.2 → 1.0, 1.2, 1.4, …
        assertTrue(isMajorTick(value = 1.4f, majorEvery = 0.2f))
    }

    @Test fun isMajorTickFalseAtMinorSteps() {
        assertFalse(isMajorTick(value = 0.6f, majorEvery = 0.5f))
        assertFalse(isMajorTick(value = 1.3f, majorEvery = 0.2f))
        assertFalse(isMajorTick(value = 190f, majorEvery = 100f))
        // 80 wpm is the range floor but NOT an integer multiple of 100 — the design intentionally
        // draws only 100, 200, …, 600 as majors on the wpm slider.
        assertFalse(isMajorTick(value = 80f, majorEvery = 100f))
    }

    @Test fun isMajorTickToleratesFloatDrift() {
        // 0.1 arithmetic in Float drifts; 0.2 * 5 lands near 1.0 but not exactly.
        val drifted = 0.2f + 0.2f + 0.2f + 0.2f + 0.2f
        assertTrue(isMajorTick(drifted, majorEvery = 0.2f))
    }
}
