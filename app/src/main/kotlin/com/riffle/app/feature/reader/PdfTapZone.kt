package com.riffle.app.feature.reader

/**
 * Classifies a short tap inside the PDF reader by horizontal position.
 *
 * The selection overlay sits above PDFView and owns tap detection, so
 * Readium's DirectionalNavigationAdapter never sees taps. This helper
 * recreates the adapter's left-third / center / right-third split so
 * edge taps still flip pages and the center area still toggles chrome.
 */
enum class PdfTapZone { LeftEdge, Center, RightEdge }

object PdfTapZoneClassifier {
    private const val EDGE_FRACTION = 0.30f

    fun classify(touchX: Float, viewWidth: Float): PdfTapZone {
        if (viewWidth <= 0f) return PdfTapZone.Center
        val frac = touchX / viewWidth
        return when {
            frac < EDGE_FRACTION -> PdfTapZone.LeftEdge
            frac > 1f - EDGE_FRACTION -> PdfTapZone.RightEdge
            else -> PdfTapZone.Center
        }
    }
}
