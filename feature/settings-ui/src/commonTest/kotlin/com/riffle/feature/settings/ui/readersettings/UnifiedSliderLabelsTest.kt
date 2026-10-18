package com.riffle.feature.settings.ui.readersettings

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

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
        assertTrue(isMajorTick(value = 100f, majorEvery = 100f))
        assertTrue(isMajorTick(value = 500f, majorEvery = 100f))
        assertTrue(isMajorTick(value = 1.0f, majorEvery = 0.5f))
        assertTrue(isMajorTick(value = 2.5f, majorEvery = 0.5f))
        assertTrue(isMajorTick(value = 1.4f, majorEvery = 0.2f))
    }

    @Test fun isMajorTickFalseAtMinorSteps() {
        assertFalse(isMajorTick(value = 0.6f, majorEvery = 0.5f))
        assertFalse(isMajorTick(value = 1.3f, majorEvery = 0.2f))
        assertFalse(isMajorTick(value = 190f, majorEvery = 100f))
        assertFalse(isMajorTick(value = 80f, majorEvery = 100f))
    }

    @Test fun isMajorTickToleratesFloatDrift() {
        val drifted = 0.2f + 0.2f + 0.2f + 0.2f + 0.2f
        assertTrue(isMajorTick(drifted, majorEvery = 0.2f))
    }
}
