package com.riffle.app.feature.reader.cbz

import com.riffle.core.domain.comic.PanelOverflowBehavior
import com.riffle.core.domain.comic.panel.PagePanels
import com.riffle.core.domain.comic.panel.PanelRegion
import com.riffle.core.domain.comic.panel.PanelSource
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins [shouldHoldForSmartSeam]: while the SMART_SPLIT seam decode is in flight, effectivePanels
 * must withhold its result for pages that would actually split — otherwise the screen renders an
 * interim centre-split and visibly re-splits (a focus jump) when the real seam arrives.
 */
class SmartSeamHoldTest {

    private val vpW = 1080
    private val vpH = 1920

    // Full-width banner on a 1080×1500 page — genuinely splittable in portrait.
    private val splittablePage = PagePanels(
        pageIndex = 0,
        imageWidth = 1080,
        imageHeight = 1500,
        panels = listOf(PanelRegion(0, 0, 1080, 300)),
        source = PanelSource.Auto,
    )

    // Normal half-width panel — nothing on the page would split.
    private val unsplittablePage = splittablePage.copy(
        panels = listOf(PanelRegion(0, 300, 540, 600)),
    )

    @Test
    fun holds_when_smart_split_pending_and_page_would_split() {
        assertTrue(
            shouldHoldForSmartSeam(
                PanelOverflowBehavior.SMART_SPLIT, samplerPending = true, splittablePage, vpW, vpH,
            ),
        )
    }

    @Test
    fun does_not_hold_once_sampler_resolved() {
        assertFalse(
            shouldHoldForSmartSeam(
                PanelOverflowBehavior.SMART_SPLIT, samplerPending = false, splittablePage, vpW, vpH,
            ),
        )
    }

    @Test
    fun does_not_hold_for_plain_split() {
        // SPLIT never uses the sampler; waiting for it would be a pointless delay.
        assertFalse(
            shouldHoldForSmartSeam(
                PanelOverflowBehavior.SPLIT, samplerPending = true, splittablePage, vpW, vpH,
            ),
        )
    }

    @Test
    fun does_not_hold_when_nothing_on_the_page_would_split() {
        assertFalse(
            shouldHoldForSmartSeam(
                PanelOverflowBehavior.SMART_SPLIT, samplerPending = true, unsplittablePage, vpW, vpH,
            ),
        )
    }
}
