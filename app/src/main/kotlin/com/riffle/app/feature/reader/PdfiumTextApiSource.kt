package com.riffle.app.feature.reader

import android.graphics.RectF
import com.riffle.core.pdfium.text.PdfiumTextApi

/**
 * Production binding: [PdfiumTextSource] adapter that forwards every call
 * straight to [PdfiumTextApi]. Trivial, untested (the API itself is covered
 * by the smoke instrumentation test in core/pdfium-text).
 */
object PdfiumTextApiSource : PdfiumTextSource {
    override fun countChars(pagePtr: Long): Int =
        PdfiumTextApi.countChars(pagePtr)

    override fun getText(pagePtr: Long, startIndex: Int, count: Int): String =
        PdfiumTextApi.getText(pagePtr, startIndex, count)

    override fun getCharBox(pagePtr: Long, charIndex: Int): RectF? =
        PdfiumTextApi.getCharBox(pagePtr, charIndex)

    override fun getCharIndexAtPos(
        pagePtr: Long, x: Double, y: Double, tolX: Double, tolY: Double,
    ): Int = PdfiumTextApi.getCharIndexAtPos(pagePtr, x, y, tolX, tolY)

    override fun rectsForRange(pagePtr: Long, startIndex: Int, count: Int): List<RectF> =
        PdfiumTextApi.rectsForRange(pagePtr, startIndex, count)
}
