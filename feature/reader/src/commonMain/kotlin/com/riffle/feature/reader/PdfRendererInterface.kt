package com.riffle.feature.reader

import kotlinx.coroutines.flow.Flow

/**
 * Platform-agnostic seam for the PDF rendering backend.
 *
 * Android implementation: Pdfium-backed renderer wired through [PdfReaderViewModel].
 * iOS implementation: [IosPdfRenderer] (wraps PDFKit's PDFView).
 *
 * Page indices are 0-based throughout this interface.
 */
interface PdfRendererInterface {

    /** Open the PDF at [filePath]. Suspends until the renderer is ready. */
    suspend fun open(filePath: String)

    /** Emits the current 0-based page index whenever the reader navigates to a new page. */
    val currentPage: Flow<Int>

    /** Total number of pages. Returns 0 before [open] completes. */
    val pageCount: Int

    /** Navigate to the given 0-based [pageIndex]. No-op if out of range. */
    fun goToPage(pageIndex: Int)

    /** Snapshot of the last reported page index; null before first [currentPage] emission. */
    fun snapshotPage(): Int?

    /** Release all resources. Safe to call multiple times. */
    fun close()
}
