package com.riffle.app.feature.reader.cbz

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pins the stale-bitmap gate in CbzPanelViewer. produceState keeps its last value while the
 * restarted producer decodes the new page, so right after a page change the state still holds
 * the PREVIOUS page's bitmap while the new page's panels may already be resolved. Rendering
 * that pairing shows the old image through the new page's panel transform — an unnecessary
 * zoom flash on every page change. The gate must treat any decode that doesn't belong to the
 * current page as still-loading.
 */
class StalePageDecodeTest {

    @Test
    fun `decode settled for a previous page is treated as loading, never rendered`() {
        val stale = CbzPageDecodeState(bitmap = null, settled = true, forPage = 3)
        val gated = decodeForPage(stale, currentPage = 4)
        assertEquals(CbzPageDecodeState(), gated)
        // And the derived content state is Loading — not Error, not Image.
        assertEquals(CbzPageContent.Loading, cbzPageContent(gated.bitmap, gated.settled))
    }

    @Test
    fun `decode for the current page passes through untouched`() {
        val fresh = CbzPageDecodeState(bitmap = null, settled = true, forPage = 4)
        assertEquals(fresh, decodeForPage(fresh, currentPage = 4))
    }

    @Test
    fun `initial state belongs to no page and is treated as loading`() {
        val initial = CbzPageDecodeState()
        val gated = decodeForPage(initial, currentPage = 0)
        assertEquals(CbzPageContent.Loading, cbzPageContent(gated.bitmap, gated.settled))
    }
}
