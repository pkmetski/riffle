package com.riffle.app.feature.reader

import org.junit.Assert.assertEquals
import org.junit.Test

class PdfTapZoneClassifierTest {

    private val width = 1000f

    @Test fun leftEdge_under30Percent() {
        assertEquals(PdfTapZone.LeftEdge, PdfTapZoneClassifier.classify(0f, width))
        assertEquals(PdfTapZone.LeftEdge, PdfTapZoneClassifier.classify(299f, width))
    }

    @Test fun rightEdge_over70Percent() {
        assertEquals(PdfTapZone.RightEdge, PdfTapZoneClassifier.classify(701f, width))
        assertEquals(PdfTapZone.RightEdge, PdfTapZoneClassifier.classify(1000f, width))
    }

    @Test fun centerBand_30to70Percent() {
        assertEquals(PdfTapZone.Center, PdfTapZoneClassifier.classify(300f, width))
        assertEquals(PdfTapZone.Center, PdfTapZoneClassifier.classify(500f, width))
        assertEquals(PdfTapZone.Center, PdfTapZoneClassifier.classify(700f, width))
    }

    @Test fun centerRight_atRightEdge_isRightEdge() {
        // Mirrors the harness test's `click(centerRight)` — the rightmost edge,
        // which must flip the page forward.
        assertEquals(PdfTapZone.RightEdge, PdfTapZoneClassifier.classify(width, width))
    }

    @Test fun zeroWidth_defaultsToCenter() {
        assertEquals(PdfTapZone.Center, PdfTapZoneClassifier.classify(123f, 0f))
    }
}
