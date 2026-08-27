package com.riffle.core.domain.comic.panel

class PanelOrchestrator(
    val config: PanelDetectionConfig = PanelDetectionConfig(),
    private val store: PanelStore,
    private val decoder: PageImageDecoder,
) : PanelEngine {

    private val detector: PanelDetector = PanelDetector(config)
    private val orderer: PanelOrderer = PanelOrderer()

    override fun forBook(bookId: String, imageBytes: (Int) -> ByteArray): PanelEngine.Book =
        Book(bookId = bookId, imageBytes = imageBytes)

    inner class Book internal constructor(
        val bookId: String,
        private val imageBytes: (Int) -> ByteArray,
    ) : PanelEngine.Book {

        override fun resolvePage(pageIndex: Int): PagePanels {
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
