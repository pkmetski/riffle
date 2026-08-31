package com.riffle.app.feature.reader.cbz

import com.riffle.core.domain.comic.panel.PanelFitTransform
import com.riffle.core.domain.comic.panel.PanelRegion
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pins the viewport half of the "spurious pan" contract in CbzPanelViewer: while the viewport
 * is unmeasured, PanelFitTransform must return Identity so the Animatables (re-keyed on
 * viewport size) initialize to a neutral transform and never interpolate from garbage.
 * The overflow-split half of the bug (a full-page splash panel being sliced into
 * pan-only segments) is pinned in PanelOverflowTransformTest.
 */
class PanelShouldSnapTest {

    @Test
    fun zero_viewport_produces_identity_transform() {
        val t = PanelFitTransform.compute(
            viewportWidth = 0,
            viewportHeight = 0,
            imageWidth = 1080,
            imageHeight = 1920,
            panel = PanelRegion(100, 100, 800, 1000),
        )
        assertEquals(PanelFitTransform.Identity, t)
    }
}
