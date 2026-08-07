package com.riffle.app.feature.reader.cbz

import com.riffle.core.domain.CbzDownloadResult
import com.riffle.core.domain.CbzOpenResult
import com.riffle.core.domain.CbzRepository
import com.riffle.core.models.EbookFormat
import com.riffle.core.models.LibraryItem
import java.io.File
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class NetworkImageSourceTest {

    private val fakeBytes = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47)

    private val fakeRepo = object : CbzRepository {
        var requestedSourceId: String? = null
        var requestedItemId: String? = null
        var requestedPageIndex: Int = -1

        override suspend fun openCbz(item: LibraryItem): CbzOpenResult = CbzOpenResult.Offline
        override suspend fun downloadCbz(item: LibraryItem, onProgress: (Long, Long) -> Unit): CbzDownloadResult =
            CbzDownloadResult.Success
        override suspend fun removeDownload(sourceId: String, itemId: String) {}
        override fun isDownloaded(sourceId: String, itemId: String): Boolean = false
        override fun isCached(sourceId: String, itemId: String): Boolean = false
        override suspend fun saveReadingPosition(itemId: String, locatorJson: String) {}
        override suspend fun supportsStreaming(sourceId: String): Boolean = true
        var requestedMaxWidth: Int? = null
        override suspend fun fetchStreamingPageImage(sourceId: String, itemId: String, pageIndex: Int, maxWidth: Int?): ByteArray {
            requestedSourceId = sourceId
            requestedItemId = itemId
            requestedPageIndex = pageIndex
            requestedMaxWidth = maxWidth
            return fakeBytes
        }
        override suspend fun awaitCachedFile(item: LibraryItem): File? = null
    }

    @Test fun `pageCount returns the count passed at construction`() {
        val source = NetworkImageSource("src", "item", 42, fakeRepo)
        assertEquals(42, source.pageCount)
    }

    @Test fun `imageBytes delegates to repository with correct args`() {
        val source = NetworkImageSource("src1", "item7", 10, fakeRepo)
        val result = source.imageBytes(3)
        assertArrayEquals(fakeBytes, result)
        assertEquals("src1", fakeRepo.requestedSourceId)
        assertEquals("item7", fakeRepo.requestedItemId)
        assertEquals(3, fakeRepo.requestedPageIndex)
    }

    @Test fun `openStream wraps imageBytes in a ByteArrayInputStream`() {
        val source = NetworkImageSource("src", "item", 5, fakeRepo)
        val stream = source.openStream(0)
        val read = stream.readBytes()
        assertArrayEquals(fakeBytes, read)
    }

    @Test fun `thumbnailWidth is forwarded as maxWidth to repository`() {
        val source = NetworkImageSource("src", "item", 10, fakeRepo, thumbnailWidth = 300)
        source.imageBytes(2)
        assertEquals(300, fakeRepo.requestedMaxWidth)
    }

    @Test fun `null thumbnailWidth passes null maxWidth to repository`() {
        val source = NetworkImageSource("src", "item", 10, fakeRepo, thumbnailWidth = null)
        source.imageBytes(2)
        assertEquals(null, fakeRepo.requestedMaxWidth)
    }

    @Test fun `byte cache prevents second network request for same pageIndex`() {
        var callCount = 0
        val countingRepo = object : CbzRepository by fakeRepo {
            override suspend fun fetchStreamingPageImage(sourceId: String, itemId: String, pageIndex: Int, maxWidth: Int?): ByteArray {
                callCount++
                return fakeBytes
            }
        }
        val source = NetworkImageSource("src", "item", 10, countingRepo)
        source.imageBytes(5)
        source.imageBytes(5) // same index — should hit cache
        assertEquals("expected only 1 network call due to byte cache", 1, callCount)
    }
}
