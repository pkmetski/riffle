package com.riffle.core.domain.comic.panel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PanelOrchestratorTest {

    @Test
    fun `cache hit returns stored panels without invoking decoder`() {
        val store = InMemoryPanelStore()
        val cached = PagePanels(
            pageIndex = 3,
            imageWidth = 400,
            imageHeight = 560,
            panels = listOf(PanelRegion(10, 10, 100, 100)),
            source = PanelSource.Auto,
        )
        store.save("book-1", cached)

        val decoder = ThrowingDecoder()
        val orchestrator = PanelOrchestrator(store, decoder)
        val book = orchestrator.forBook(
            bookId = "book-1",
            imageBytes = { error("should not be called on cache hit") },
        )

        assertEquals(cached, book.resolvePage(3))
    }

    @Test
    fun `on a cache miss the detector runs and result is cached`() {
        val store = InMemoryPanelStore()
        val syntheticGrid = buildGridWithFourPanels(width = 400, height = 560)
        val decoder = FakeDecoder(
            PageImageDecoder.Result(
                grid = syntheticGrid,
                originalWidth = 400,
                originalHeight = 560,
            ),
        )
        val orchestrator = PanelOrchestrator(store, decoder)
        val book = orchestrator.forBook(
            bookId = "book-1",
            imageBytes = { ByteArray(1) },
        )

        val page = book.resolvePage(0)
        assertEquals(PanelSource.Auto, page.source)
        assertEquals(4, page.panels.size)
        assertNotNull(store.load("book-1", 0))
        // Row-major order — first panel's origin is the top-left one. Exact bounds vary a
        // few pixels depending on which detection path fires (CC vs projection) and its
        // trim/tighten thresholds, so assert an approximate match.
        assertTrue("expected first panel near (20,20), got (${page.panels[0].x},${page.panels[0].y})",
            page.panels[0].x in 15..25 && page.panels[0].y in 15..25)
        assertTrue("expected second panel near (210,20), got (${page.panels[1].x},${page.panels[1].y})",
            page.panels[1].x in 205..215 && page.panels[1].y in 15..25)
    }

    @Test
    fun `decoder returning null produces a fallback whole-page result`() {
        val store = InMemoryPanelStore()
        val decoder = FakeDecoder(null)
        val orchestrator = PanelOrchestrator(store, decoder)
        val book = orchestrator.forBook(
            bookId = "book-1",
            imageBytes = { ByteArray(1) },
        )

        val page = book.resolvePage(0)
        assertEquals(PanelSource.Fallback, page.source)
        assertEquals(1, page.panels.size)
    }

    @Test
    fun `imageBytes throwing produces a fallback whole-page result`() {
        val store = InMemoryPanelStore()
        val orchestrator = PanelOrchestrator(store, ThrowingDecoder())
        val book = orchestrator.forBook(
            bookId = "book-1",
            imageBytes = { error("archive read failed") },
        )

        val page = book.resolvePage(0)
        assertEquals(PanelSource.Fallback, page.source)
    }

    @Test
    fun `resolvePage is idempotent - second call hits the store`() {
        val store = InMemoryPanelStore()
        val grid = buildGridWithFourPanels(400, 560)
        val decoder = CountingDecoder(
            PageImageDecoder.Result(grid, 400, 560),
        )
        val orchestrator = PanelOrchestrator(store, decoder)
        val book = orchestrator.forBook(
            bookId = "book-1",
            imageBytes = { ByteArray(1) },
        )

        book.resolvePage(0)
        book.resolvePage(0)

        assertEquals("decoder must be called only once", 1, decoder.callCount)
    }

    @Test
    fun `store keyed by bookId isolates two books`() {
        val store = InMemoryPanelStore()
        val page = PagePanels(0, 400, 560, listOf(PanelRegion(1, 1, 2, 2)), PanelSource.Auto)
        store.save("book-A", page)
        assertNotNull(store.load("book-A", 0))
        assertNull(store.load("book-B", 0))
    }

    // --- Fixtures / fakes ---

    private class InMemoryPanelStore : PanelStore {
        private val data = mutableMapOf<Pair<String, Int>, PagePanels>()
        override fun load(bookId: String, pageIndex: Int) = data[bookId to pageIndex]
        override fun loadAll(bookId: String) =
            data.filterKeys { it.first == bookId }.mapKeys { it.key.second }
        override fun save(bookId: String, page: PagePanels) {
            data[bookId to page.pageIndex] = page
        }
        override fun saveAll(bookId: String, pages: Collection<PagePanels>) {
            pages.forEach { save(bookId, it) }
        }
        override fun clear(bookId: String) {
            data.keys.removeAll { it.first == bookId }
        }
    }

    private class FakeDecoder(private val result: PageImageDecoder.Result?) : PageImageDecoder {
        override fun decode(bytes: ByteArray, targetLongEdge: Int) = result
    }

    private class CountingDecoder(private val result: PageImageDecoder.Result) : PageImageDecoder {
        var callCount = 0
        override fun decode(bytes: ByteArray, targetLongEdge: Int): PageImageDecoder.Result {
            callCount++
            return result
        }
    }

    private class ThrowingDecoder : PageImageDecoder {
        override fun decode(bytes: ByteArray, targetLongEdge: Int) =
            error("decoder should not be invoked")
    }

    private fun buildGridWithFourPanels(width: Int, height: Int): PixelGrid {
        val luma = ByteArray(width * height) { 240.toByte() }
        fun rect(x: Int, y: Int, w: Int, h: Int) {
            for (yy in y until (y + h).coerceAtMost(height)) {
                for (xx in x until (x + w).coerceAtMost(width)) {
                    luma[yy * width + xx] = 20.toByte()
                }
            }
        }
        rect(20, 20, 170, 250)
        rect(210, 20, 170, 250)
        rect(20, 290, 170, 250)
        rect(210, 290, 170, 250)
        assertTrue(luma.any { it == 20.toByte() })
        return PixelGrid(width, height, luma)
    }
}
