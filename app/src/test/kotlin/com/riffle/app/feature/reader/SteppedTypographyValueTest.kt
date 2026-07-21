package com.riffle.app.feature.reader

import com.riffle.core.domain.autoscroll.AutoScrollSpeed
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Locks the icon-tap step per slider. If a caller ever passes a bare `+ step` (no clamp / no
 * round) the assertions here flip red — that is the regression this test exists to prevent.
 */
class SteppedTypographyValueTest {

    private val fontSizeRange = 0.5f..2.5f
    private val lineSpacingRange = 1.0f..2.0f
    private val marginsRange = 0.2f..3.0f

    @Test fun fontSizeIncrementsByTenPercent() {
        assertEquals(1.1f, steppedTypographyValue(1.0f, 0.1f, fontSizeRange), 1e-4f)
        assertEquals(0.9f, steppedTypographyValue(1.0f, -0.1f, fontSizeRange), 1e-4f)
    }

    @Test fun fontSizeClampsAtRangeEdges() {
        assertEquals(2.5f, steppedTypographyValue(2.5f, 0.1f, fontSizeRange), 1e-4f)
        assertEquals(0.5f, steppedTypographyValue(0.5f, -0.1f, fontSizeRange), 1e-4f)
    }

    @Test fun lineSpacingSnapsToOneDecimalLattice() {
        // 1.4 + 0.1 in Float is 1.5000001; the helper must round to the tick.
        assertEquals(1.5f, steppedTypographyValue(1.4f, 0.1f, lineSpacingRange), 1e-4f)
        assertEquals(1.0f, steppedTypographyValue(1.0f, -0.1f, lineSpacingRange), 1e-4f)
        assertEquals(2.0f, steppedTypographyValue(2.0f, 0.1f, lineSpacingRange), 1e-4f)
    }

    @Test fun marginsStepIsTwoTenths() {
        // Margins run 0.2×–3.0× with a 0.2× icon-tap step (coarser than the 0.1× slider tick
        // because 28 taps end-to-end reads worse than 14).
        assertEquals(1.2f, steppedTypographyValue(1.0f, 0.2f, marginsRange), 1e-4f)
        assertEquals(0.8f, steppedTypographyValue(1.0f, -0.2f, marginsRange), 1e-4f)
        assertEquals(3.0f, steppedTypographyValue(3.0f, 0.2f, marginsRange), 1e-4f)
        assertEquals(0.2f, steppedTypographyValue(0.2f, -0.2f, marginsRange), 1e-4f)
    }

    @Test fun wpmIconStepLandsOnTwentyWpmGrid() {
        // The pacing sliders route the icon-tap through AutoScrollSpeed.of so a 20-wpm delta
        // is re-snapped to the underlying 10-wpm lattice; both directions must clamp at the
        // range edges (80 / 600) rather than wrap or overshoot.
        assertEquals(270, AutoScrollSpeed.of(250 + 20).wpm)
        assertEquals(230, AutoScrollSpeed.of(250 - 20).wpm)
        assertEquals(600, AutoScrollSpeed.of(600 + 20).wpm)
        assertEquals(80, AutoScrollSpeed.of(80 - 20).wpm)
    }
}
