package com.riffle.core.domain.comic.panel

/**
 * Given a book, resolves per-page [PagePanels] using this priority chain:
 *
 *  1. [store] cache hit (returned immediately, no work).
 *  2. On-device auto-detection via [detector] over the page image (decoded through [decoder]).
 *
 * The result of step 2 is written back to [store] before being returned. Panel regions are
 * always returned in reading order via [orderer].
 *
 * Coroutine dispatching is the caller's responsibility — [resolvePage] is a plain function so
 * it can be invoked from `withContext(Dispatchers.Default)` in a prefetcher or synchronously
 * in a test. Bitmap decode dominates the cost budget; run this off the main thread.
 *
 * The ACBF-sidecar override path from ADR 0043 §3 is intentionally not wired — carrying
 * per-page image dimensions through the reader for the one uncommon input class isn't worth
 * the plumbing; [AcbfPanelReader] remains available for a future revival.
 */
class PanelOrchestrator(
    private val store: PanelStore,
    private val decoder: PageImageDecoder,
    private val detector: PanelDetector = PanelDetector(),
    private val orderer: PanelOrderer = PanelOrderer(),
) {
    /**
     * Bind an archive-specific view of the orchestrator. Held for the lifetime of a reader
     * session and passed to `resolvePage(index)`.
     *
     * @param bookId a stable identifier that already incorporates the archive's content hash.
     * @param imageBytes a function that returns the raw image bytes for a given page index.
     */
    fun forBook(
        bookId: String,
        imageBytes: (Int) -> ByteArray,
    ): Book = Book(bookId = bookId, imageBytes = imageBytes)

    inner class Book internal constructor(
        val bookId: String,
        private val imageBytes: (Int) -> ByteArray,
    ) {
        /**
         * Return panel regions for a page, computing and caching if necessary. Always returns a
         * non-empty [PagePanels] — a Fallback source signals "render Fit Whole for this page."
         */
        fun resolvePage(pageIndex: Int): PagePanels {
            store.load(bookId, pageIndex)?.let { return it }
            val resolved = resolveUncached(pageIndex)
            store.save(bookId, resolved)
            return resolved
        }

        private fun resolveUncached(pageIndex: Int): PagePanels {
            val bytes = runCatching { imageBytes(pageIndex) }.getOrNull()
                ?: return fitWhole(pageIndex, 1, 1)
            val decoded = decoder.decode(bytes)
                ?: return fitWhole(pageIndex, 1, 1)
            val detected = detector.detect(
                grid = decoded.grid,
                pageIndex = pageIndex,
                originalWidth = decoded.originalWidth,
                originalHeight = decoded.originalHeight,
            )
            return detected.copy(panels = orderer.order(detected.panels))
        }

        private fun fitWhole(pageIndex: Int, w: Int, h: Int): PagePanels = PagePanels(
            pageIndex = pageIndex,
            imageWidth = w,
            imageHeight = h,
            panels = listOf(PanelRegion(0, 0, w, h)),
            source = PanelSource.Fallback,
        )
    }
}
