package com.riffle.app.feature.reader.cbz

import java.io.InputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * Unit tests for the pure state-transition functions extracted from [CbzReaderViewModel.swapToLocalArchive].
 * The ViewModel extends AndroidViewModel and cannot be instantiated in JVM tests; the functions are
 * extracted so the key behaviors can be pinned without an Android dependency.
 */
class CbzArchiveSwapTest {

    private fun fakeSource(count: Int = 10): CbzImageSource = object : CbzImageSource {
        override val pageCount: Int = count
        override fun imageBytes(pageIndex: Int): ByteArray = ByteArray(0)
        override fun openStream(pageIndex: Int): InputStream = ByteArray(0).inputStream()
    }

    private fun readyState(
        pageCount: Int = 10,
        imageSource: CbzImageSource = fakeSource(pageCount),
        thumbnailSource: CbzImageSource? = fakeSource(pageCount),
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
        assertNull("thumbnailSource must be null after archive swap", result.thumbnailSource)
    }

    @Test fun `imageSource is replaced with the new source`() {
        val current = readyState()
        val newSource = fakeSource()
        val result = computeArchiveSwapState(current, actualPageCount = 10, newSource = newSource)
        assertSame("imageSource must be the new archive source", newSource, result.imageSource)
    }

    @Test fun `pageCount is updated to actualPageCount when positive`() {
        val current = readyState(pageCount = 15)
        val result = computeArchiveSwapState(current, actualPageCount = 12, newSource = fakeSource())
        assertEquals("pageCount must use actualPageCount when > 0", 12, result.pageCount)
    }

    @Test fun `pageCount falls back to current when actualPageCount is zero`() {
        val current = readyState(pageCount = 15)
        val result = computeArchiveSwapState(current, actualPageCount = 0, newSource = fakeSource())
        assertEquals("pageCount must keep current value when actualPageCount is 0", 15, result.pageCount)
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

    @Test fun `returns null when actualPageCount is zero (no clamping, keep streaming count)`() {
        assertNull(clampPageForSwap(currentPage = 99, actualPageCount = 0))
    }
}
