package com.riffle.app.feature.reader

import android.graphics.RectF

/**
 * Minimal abstraction over the Pdfium text APIs that [PdfTextResolver]
 * needs. Production binds to `com.riffle.core.pdfium.text.PdfiumTextApi`;
 * JVM tests bind to an in-memory fake driven by a fixture char list.
 *
 * All methods are page-scoped: the `pagePtr` argument is opaque to this
 * interface and uniquely identifies one open Pdfium text-page.
 */
interface PdfiumTextSource {
    fun countChars(pagePtr: Long): Int
    fun getText(pagePtr: Long, startIndex: Int, count: Int): String
    fun getCharBox(pagePtr: Long, charIndex: Int): RectF?
    fun getCharIndexAtPos(
        pagePtr: Long,
        x: Double,
        y: Double,
        tolX: Double,
        tolY: Double,
    ): Int
    fun rectsForRange(pagePtr: Long, startIndex: Int, count: Int): List<RectF>
}
