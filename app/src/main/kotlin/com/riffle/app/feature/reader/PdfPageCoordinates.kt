package com.riffle.app.feature.reader

import android.graphics.RectF
import com.github.barteksc.pdfviewer.PDFView

/**
 * Coordinate-conversion helpers between barteksc PDFView's screen-space pixels
 * and Pdfium's PDF-user-space points. The PDF reader's selection overlay
 * fully overlaps PDFView, so a touch point in the overlay is the same as
 * a touch point in PDFView; this file just does the math.
 *
 * PDFView lays out pages vertically (default scrollAxis), stacked from the
 * document origin (0, 0). The visible viewport origin is offset from the
 * document origin by `(currentXOffset, currentYOffset)` — both negative when
 * the document has been scrolled. So
 *   screen_pos = doc_pos + currentOffset
 * Pages are horizontally centered within the view. Each page's screen size
 * (`getPageSize(i)`) is in pixels AT THE CURRENT ZOOM, so dividing by zoom
 * gives the unit-size cached layout.
 *
 * PDF user-space coordinates have origin at the page's *bottom-left*; Y
 * grows upward. PDFView's screen pixels have origin at the top-left with Y
 * down. So Y must be flipped during conversion.
 */
data class PdfPagePoint(
    val pageIndex: Int,
    /** Coordinates in PDF user-space points; origin bottom-left, Y up. */
    val xPoints: Double,
    val yPoints: Double,
    /** Page natural dimensions in PDF points. */
    val pageWidthPoints: Double,
    val pageHeightPoints: Double,
)

object PdfPageCoordinates {

    /**
     * Convert a [touchX], [touchY] in [pdfView]'s coordinate space to a
     * (page, PDF-point) tuple. Returns null if the touch falls in the
     * gap between pages or outside the document entirely.
     *
     * Uses [getPageWidthPoints]/[getPageHeightPoints] as the source of truth
     * for each page's PDF-point dimensions because PDFView's getPageSize
     * returns pixels-at-zoom, not points; the two are related by an opaque
     * DPI-based scale that we'd otherwise have to reconstruct.
     */
    fun screenToPdf(
        pdfView: PDFView,
        touchX: Float,
        touchY: Float,
        getPageWidthPoints: (Int) -> Double,
        getPageHeightPoints: (Int) -> Double,
        pageCount: Int,
    ): PdfPagePoint? {
        val zoom = pdfView.zoom
        if (zoom <= 0f) return null

        // 1. Touch in document coordinates (screen-pixels, but in the doc's
        //    layout space rather than the view's). Pages stack vertically
        //    starting at doc_y = 0.
        val docTouchX = touchX - pdfView.currentXOffset
        val docTouchY = touchY - pdfView.currentYOffset

        // 2. Walk pages top-down to find the one containing docTouchY.
        var accY = 0f
        for (i in 0 until pageCount) {
            val pageSize = pdfView.getPageSize(i)
            val pageScreenW = pageSize.width  // at current zoom
            val pageScreenH = pageSize.height
            if (docTouchY in accY..(accY + pageScreenH)) {
                // 3. Horizontal centering within the view.
                val viewW = pdfView.width.toFloat()
                val pageLeft = (viewW - pageScreenW) / 2f
                val pageLocalScreenX = docTouchX - pageLeft
                val pageLocalScreenY = docTouchY - accY
                if (pageLocalScreenX < 0 || pageLocalScreenX > pageScreenW) {
                    // Touch was outside the page horizontally (margin).
                    return null
                }
                val pdfW = getPageWidthPoints(i)
                val pdfH = getPageHeightPoints(i)
                if (pdfW <= 0.0 || pdfH <= 0.0) return null
                // 4. Pixels → points using the per-page ratio.
                val pointsPerPxX = pdfW / pageScreenW
                val pointsPerPxY = pdfH / pageScreenH
                val pdfX = pageLocalScreenX * pointsPerPxX
                // PDF Y grows up; flip from screen Y (down).
                val pdfY = pdfH - (pageLocalScreenY * pointsPerPxY)
                return PdfPagePoint(
                    pageIndex = i,
                    xPoints = pdfX,
                    yPoints = pdfY,
                    pageWidthPoints = pdfW,
                    pageHeightPoints = pdfH,
                )
            }
            accY += pageScreenH
            // PDFView's default page spacing is 0; if a refresh ever surfaces
            // non-zero spacing it should be added here.
        }
        return null
    }

    /**
     * Inverse of [screenToPdf]: convert a PDF rectangle (user-space, Y up)
     * on [pageIndex] to a screen-space rectangle suitable for drawing on a
     * Canvas overlaying [pdfView]. Returns null when [pageIndex] is invalid.
     */
    fun pdfRectToScreen(
        pdfView: PDFView,
        pageIndex: Int,
        pdfRect: RectF,
        pageWidthPoints: Double,
        pageHeightPoints: Double,
        pageCount: Int,
    ): RectF? {
        if (pageIndex !in 0 until pageCount) return null
        if (pageWidthPoints <= 0.0 || pageHeightPoints <= 0.0) return null
        val pageSize = pdfView.getPageSize(pageIndex)
        if (pageSize.width <= 0f || pageSize.height <= 0f) return null

        // Accumulate prior pages' heights for the page's doc-Y top.
        var accY = 0f
        for (i in 0 until pageIndex) accY += pdfView.getPageSize(i).height

        val viewW = pdfView.width.toFloat()
        val pageLeftDoc = (viewW - pageSize.width) / 2f
        val pageTopDoc = accY

        val pxPerPointX = pageSize.width / pageWidthPoints
        val pxPerPointY = pageSize.height / pageHeightPoints

        // PDF y grows up; flip when going to screen y.
        // PDF top edge = pdfRect.top is the HIGHER y; pdfRect.bottom is the LOWER.
        val pdfTopY = maxOf(pdfRect.top, pdfRect.bottom)
        val pdfBottomY = minOf(pdfRect.top, pdfRect.bottom)
        val pdfLeftX = minOf(pdfRect.left, pdfRect.right)
        val pdfRightX = maxOf(pdfRect.left, pdfRect.right)

        val screenLeft = pageLeftDoc + pdfLeftX.toFloat() * pxPerPointX.toFloat() + pdfView.currentXOffset
        val screenRight = pageLeftDoc + pdfRightX.toFloat() * pxPerPointX.toFloat() + pdfView.currentXOffset
        val screenTop = pageTopDoc + ((pageHeightPoints - pdfTopY) * pxPerPointY).toFloat() + pdfView.currentYOffset
        val screenBottom = pageTopDoc + ((pageHeightPoints - pdfBottomY) * pxPerPointY).toFloat() + pdfView.currentYOffset

        return RectF(screenLeft, screenTop, screenRight, screenBottom)
    }
}
