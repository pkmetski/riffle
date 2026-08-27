package com.riffle.core.domain.comic.panel

/**
 * The public API surface for panel detection (ADR 0063). All app code that drives
 * panel detection must go through this interface — no direct references to
 * [PanelOrchestrator], [PanelDetector], or [PanelMaskBinarizer].
 *
 * When the engine is extracted to a private KMP library, this interface defines the
 * stable contract the library must satisfy.
 */
interface PanelEngine {

    /**
     * Bind a book-scoped handle for the given archive. The handle is valid for the
     * lifetime of a reader session. [imageBytes] must return the raw compressed image
     * bytes for a given page index.
     */
    fun forBook(bookId: String, imageBytes: (Int) -> ByteArray): Book

    /** A book-scoped view of the engine, bound to a specific archive. */
    interface Book {
        /**
         * Return panel regions for [pageIndex], computing and caching if necessary.
         * Always returns a non-empty [PagePanels] — [PanelSource.Fallback] signals
         * "render Fit Whole for this page."
         *
         * Coroutine dispatching is the caller's responsibility.
         */
        fun resolvePage(pageIndex: Int): PagePanels
    }
}
