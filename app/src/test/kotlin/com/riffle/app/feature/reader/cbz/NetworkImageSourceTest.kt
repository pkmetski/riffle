package com.riffle.app.feature.reader.cbz

import com.riffle.core.domain.CbzDownloadResult
import com.riffle.core.domain.CbzOpenResult
import com.riffle.core.domain.CbzRepository
import com.riffle.core.models.EbookFormat
import com.riffle.core.models.LibraryItem
import java.io.File
import java.util.Collections
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
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

    // --- Read-ahead (streaming-phase page-turn latency fix) ---

    private class RecordingRepo(base: CbzRepository, private val bytes: ByteArray) : CbzRepository by base {
        val fetchedIndices: MutableList<Int> = Collections.synchronizedList(mutableListOf())
        override suspend fun fetchStreamingPageImage(sourceId: String, itemId: String, pageIndex: Int, maxWidth: Int?): ByteArray {
            fetchedIndices.add(pageIndex)
            return bytes
        }
    }

    private fun TestScope.readAheadSource(repo: CbzRepository, pageCount: Int, readAheadCount: Int = 2) =
        NetworkImageSource(
            "src", "item", pageCount, repo,
            readAheadScope = this,
            readAheadCount = readAheadCount,
            readAheadDispatcher = StandardTestDispatcher(testScheduler),
        )

    @Test fun `accessing a page prefetches the next readAheadCount pages`() = runTest {
        val repo = RecordingRepo(fakeRepo, fakeBytes)
        val source = readAheadSource(repo, pageCount = 10)
        source.imageBytes(4)
        advanceUntilIdle()
        assertEquals(listOf(4, 5, 6), repo.fetchedIndices.sorted())
    }

    @Test fun `prefetched page is served from cache without a new network request`() = runTest {
        val repo = RecordingRepo(fakeRepo, fakeBytes)
        val source = readAheadSource(repo, pageCount = 10)
        source.imageBytes(4)
        advanceUntilIdle()
        repo.fetchedIndices.clear()
        source.imageBytes(5) // the page turn the user waits on — must be a cache hit
        assertEquals("page 5 must not be re-fetched after read-ahead", emptyList<Int>(), repo.fetchedIndices.filter { it == 5 })
    }

    @Test fun `read-ahead stops at the last page`() = runTest {
        val repo = RecordingRepo(fakeRepo, fakeBytes)
        val source = readAheadSource(repo, pageCount = 10)
        source.imageBytes(9)
        advanceUntilIdle()
        assertEquals(listOf(9), repo.fetchedIndices.toList())
    }

    @Test fun `read-ahead is off by default`() {
        val repo = RecordingRepo(fakeRepo, fakeBytes)
        val source = NetworkImageSource("src", "item", 10, repo)
        source.imageBytes(4)
        assertEquals(listOf(4), repo.fetchedIndices.toList())
    }

    @Test fun `read-ahead does not duplicate an in-flight prefetch`() = runTest {
        val repo = RecordingRepo(fakeRepo, fakeBytes)
        val source = readAheadSource(repo, pageCount = 10)
        source.imageBytes(4)
        source.imageBytes(4) // second access before the scheduler runs — must not re-enqueue 5/6
        advanceUntilIdle()
        assertEquals(listOf(4, 5, 6), repo.fetchedIndices.sorted())
    }

    @Test fun `cache retains current page alongside read-ahead entries`() = runTest {
        val repo = RecordingRepo(fakeRepo, fakeBytes)
        val source = readAheadSource(repo, pageCount = 20)
        source.imageBytes(4) // decodeSampledBitmap bounds pass
        advanceUntilIdle() // read-ahead of 5 and 6 completes
        repo.fetchedIndices.clear()
        source.imageBytes(4) // decode pass — must still be cached after read-ahead insertions
        assertEquals(emptyList<Int>(), repo.fetchedIndices.toList())
    }
}
