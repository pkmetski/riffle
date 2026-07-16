package com.riffle.core.domain.comic.panel

/**
 * Given a book, resolves per-page [PagePanels] using this priority chain:
 *
 *  1. [store] cache hit (returned immediately, no work).
 *  2. ACBF sidecar entry, if the caller supplied one and the page has `<frame>` regions.
 *  3. On-device auto-detection via [detector] over the page image (decoded through [decoder]).
 *
 * The result of steps 2 and 3 is written back to [store] before being returned. Panel regions
 * are always returned in reading order via [orderer].
 *
 * Coroutine dispatching is the caller's responsibility — [resolvePage] is a plain function so
 * it can be invoked from `withContext(Dispatchers.Default)` in a prefetcher or synchronously
 * in a test. Bitmap decode dominates the cost budget; run this off the main thread.
 */
class PanelOrchestrator(
    private val store: PanelStore,
    private val decoder: PageImageDecoder,
    private val detector: PanelDetector = PanelDetector(),
    private val orderer: PanelOrderer = PanelOrderer(),
    private val acbfReader: AcbfPanelReader = AcbfPanelReader(),
) {
    /**
     * Bind an archive-specific view of the orchestrator. Held for the lifetime of a reader
     * session and passed to `resolvePage(index)` / `preloadFromMetadata()`.
     *
     * @param bookId a stable identifier that already incorporates the archive's content hash.
     * @param imageBytes a function that returns the raw image bytes for a given page index.
     * @param acbfXml the archive's ACBF sidecar as a string, or `null` if none is present.
     * @param pageImageDimensions ordered `(width, height)` per page. Used only when the archive
     *   supplies an ACBF sidecar (to interpret its frame coordinates); may be an empty list
     *   otherwise.
     */
    fun forBook(
        bookId: String,
        imageBytes: (Int) -> ByteArray,
        acbfXml: String? = null,
        pageImageDimensions: List<Pair<Int, Int>> = emptyList(),
    ): Book = Book(
        bookId = bookId,
        imageBytes = imageBytes,
        acbfXml = acbfXml,
        pageImageDimensions = pageImageDimensions,
    )

    inner class Book internal constructor(
        val bookId: String,
        private val imageBytes: (Int) -> ByteArray,
        private val acbfXml: String?,
        private val pageImageDimensions: List<Pair<Int, Int>>,
    ) {
        private val acbfIndex: Map<Int, PagePanels> by lazy {
            if (acbfXml.isNullOrBlank()) {
                emptyMap()
            } else {
                acbfReader.read(acbfXml, pageImageDimensions).associateBy { it.pageIndex }
            }
        }

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
            acbfIndex[pageIndex]?.let { fromAcbf ->
                return fromAcbf.copy(panels = orderer.order(fromAcbf.panels))
            }
            val bytes = runCatching { imageBytes(pageIndex) }.getOrNull()
                ?: return fitWhole(pageIndex, fallbackWidth = 1, fallbackHeight = 1)
            val decoded = decoder.decode(bytes)
                ?: return fitWhole(pageIndex, fallbackWidth = 1, fallbackHeight = 1)
            val detected = detector.detect(
                grid = decoded.grid,
                pageIndex = pageIndex,
                originalWidth = decoded.originalWidth,
                originalHeight = decoded.originalHeight,
            )
            return detected.copy(panels = orderer.order(detected.panels))
        }

        private fun fitWhole(pageIndex: Int, fallbackWidth: Int, fallbackHeight: Int): PagePanels {
            val (w, h) = pageImageDimensions.getOrNull(pageIndex)
                ?: (fallbackWidth to fallbackHeight)
            return PagePanels(
                pageIndex = pageIndex,
                imageWidth = w,
                imageHeight = h,
                panels = listOf(PanelRegion(0, 0, w, h)),
                source = PanelSource.Fallback,
            )
        }
    }
}
