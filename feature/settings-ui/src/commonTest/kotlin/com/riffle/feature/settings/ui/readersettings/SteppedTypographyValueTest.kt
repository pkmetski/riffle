package com.riffle.feature.settings.ui.readersettings

import com.riffle.core.domain.autoscroll.AutoScrollSpeed
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SteppedTypographyValueTest {

    private val fontSizeRange = 0.5f..2.5f
    private val lineSpacingRange = 1.0f..2.0f
    private val marginsRange = 0.2f..3.0f

    private fun assertApprox(expected: Float, actual: Float, delta: Float = 1e-4f) {
        assertTrue(abs(actual - expected) < delta, "expected $expected but was $actual (delta $delta)")
    }

    @Test fun fontSizeIncrementsByTenPercent() {
        assertApprox(1.1f, steppedTypographyValue(1.0f, 0.1f, fontSizeRange))
        assertApprox(0.9f, steppedTypographyValue(1.0f, -0.1f, fontSizeRange))
    }

    @Test fun fontSizeClampsAtRangeEdges() {
        assertApprox(2.5f, steppedTypographyValue(2.5f, 0.1f, fontSizeRange))
        assertApprox(0.5f, steppedTypographyValue(0.5f, -0.1f, fontSizeRange))
    }

    @Test fun lineSpacingSnapsToOneDecimalLattice() {
        assertApprox(1.5f, steppedTypographyValue(1.4f, 0.1f, lineSpacingRange))
        assertApprox(1.0f, steppedTypographyValue(1.0f, -0.1f, lineSpacingRange))
        assertApprox(2.0f, steppedTypographyValue(2.0f, 0.1f, lineSpacingRange))
    }

    @Test fun marginsStepIsTwoTenths() {
        assertApprox(1.2f, steppedTypographyValue(1.0f, 0.2f, marginsRange))
        assertApprox(0.8f, steppedTypographyValue(1.0f, -0.2f, marginsRange))
        assertApprox(3.0f, steppedTypographyValue(3.0f, 0.2f, marginsRange))
        assertApprox(0.2f, steppedTypographyValue(0.2f, -0.2f, marginsRange))
    }

    @Test fun marginsIconTapFromOffGridSnapsToNearestGridPoint() {
        assertApprox(1.8f, steppedTypographyValue(1.7f, 0.2f, marginsRange))
        assertApprox(1.8f, steppedTypographyValue(1.9f, -0.2f, marginsRange))
        assertApprox(1.6f, steppedTypographyValue(1.7f, -0.2f, marginsRange))
        assertApprox(2.0f, steppedTypographyValue(1.9f, 0.2f, marginsRange))
    }

    @Test fun wpmIconStepLandsOnTwentyWpmGrid() {
        assertEquals(270, AutoScrollSpeed.of(250 + 20).wpm)
        assertEquals(230, AutoScrollSpeed.of(250 - 20).wpm)
        assertEquals(600, AutoScrollSpeed.of(600 + 20).wpm)
        assertEquals(80, AutoScrollSpeed.of(80 - 20).wpm)
    }
}
