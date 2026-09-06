package com.riffle.feature.reader

import com.riffle.core.domain.comic.ComicPageSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

/**
 * Unit tests for the pure state-transition functions extracted from
 * [CbzReaderViewModel.swapToLocalArchive]. Kept as top-level functions so the key behaviours can be
 * pinned on JVM + iOS without instantiating the ViewModel.
 */
class CbzArchiveSwapTest {

    private fun fakeSource(count: Int = 10): ComicPageSource = object : ComicPageSource {
        override val pageCount: Int = count
        override fun imageBytes(pageIndex: Int): ByteArray = ByteArray(0)
        override fun mediaType(pageIndex: Int): String = "image/jpeg"
    }

    private fun readyState(
        pageCount: Int = 10,
        imageSource: ComicPageSource = fakeSource(pageCount),
        thumbnailSource: ComicPageSource? = fakeSource(pageCount),
    ) = CbzReaderState.Ready(
        title = "Comic",
        pageCount = pageCount,
        imageSource = imageSource,
        thumbnailSource = thumbnailSource,
    )

    // --- computeArchiveSwapState ---

    @Test fun `thumbnailSource is null after swap`() {
        val current = readyState(thumbnailSource = fakeSource())
        val newSource = fakeSource()
        val result = computeArchiveSwapState(current, actualPageCount = 10, newSource = newSource)
        assertNull(result.thumbnailSource, "thumbnailSource must be null after archive swap")
    }

    @Test fun `imageSource is replaced with the new source`() {
        val current = readyState()
        val newSource = fakeSource()
        val result = computeArchiveSwapState(current, actualPageCount = 10, newSource = newSource)
        assertSame(newSource, result.imageSource, "imageSource must be the new archive source")
    }

    @Test fun `pageCount is updated to actualPageCount when positive`() {
        val current = readyState(pageCount = 15)
        val result = computeArchiveSwapState(current, actualPageCount = 12, newSource = fakeSource())
        assertEquals(12, result.pageCount, "pageCount must use actualPageCount when > 0")
    }

    @Test fun `pageCount falls back to current when actualPageCount is zero`() {
        val current = readyState(pageCount = 15)
        val result = computeArchiveSwapState(current, actualPageCount = 0, newSource = fakeSource())
        assertEquals(15, result.pageCount, "pageCount must keep current value when actualPageCount is 0")
    }

    // --- clampPageForSwap ---

    @Test fun `returns null when currentPage is within bounds`() {
        assertNull(clampPageForSwap(currentPage = 5, actualPageCount = 10))
    }

    @Test fun `returns null when currentPage equals last valid index`() {
        assertNull(clampPageForSwap(currentPage = 9, actualPageCount = 10))
    }

    @Test fun `clamps to last valid page when currentPage is out of bounds`() {
        assertEquals(9, clampPageForSwap(currentPage = 12, actualPageCount = 10))
    }

    @Test fun `returns null when actualPageCount is zero - keep streaming count`() {
        assertNull(clampPageForSwap(currentPage = 99, actualPageCount = 0))
    }
}
