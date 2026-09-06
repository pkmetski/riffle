package com.riffle.core.data.comic

import com.riffle.core.domain.CbzDownloadResult
import com.riffle.core.domain.CbzLocalSource
import com.riffle.core.domain.CbzOpenResult
import com.riffle.core.domain.CbzRepository
import com.riffle.core.models.LibraryItem
import java.util.Collections
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class NetworkComicPageSourceTest {

    private val fakeBytes = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47)

    private val fakeRepo = object : CbzRepository {
        var requestedSourceId: String? = null
        var requestedItemId: String? = null
        var requestedPageIndex: Int = -1
        var requestedMaxWidth: Int? = null

        override suspend fun openCbz(item: LibraryItem): CbzOpenResult = CbzOpenResult.Offline
        override suspend fun downloadCbz(item: LibraryItem, onProgress: (Long, Long) -> Unit): CbzDownloadResult =
            CbzDownloadResult.Success
        override suspend fun removeDownload(sourceId: String, itemId: String) {}
        override fun isDownloaded(sourceId: String, itemId: String): Boolean = false
        override fun isCached(sourceId: String, itemId: String): Boolean = false
        override suspend fun saveReadingPosition(itemId: String, locatorJson: String) {}
        override suspend fun supportsStreaming(sourceId: String): Boolean = true
        override suspend fun fetchStreamingPageImage(sourceId: String, itemId: String, pageIndex: Int, maxWidth: Int?): ByteArray {
            requestedSourceId = sourceId
            requestedItemId = itemId
            requestedPageIndex = pageIndex
            requestedMaxWidth = maxWidth
            return fakeBytes
        }
        override suspend fun awaitCachedSource(item: LibraryItem): CbzLocalSource? = null
    }

    private val noopDispatcher = StandardTestDispatcher()

    @Test fun `pageCount returns the count passed at construction`() {
        val source = NetworkComicPageSource("src", "item", 42, fakeRepo, ioDispatcher = noopDispatcher)
        assertEquals(42, source.pageCount)
    }

    @Test fun `imageBytes delegates to repository with correct args`() {
        val source = NetworkComicPageSource("src1", "item7", 10, fakeRepo, ioDispatcher = noopDispatcher)
        val result = source.imageBytes(3)
        assertArrayEquals(fakeBytes, result)
        assertEquals("src1", fakeRepo.requestedSourceId)
        assertEquals("item7", fakeRepo.requestedItemId)
        assertEquals(3, fakeRepo.requestedPageIndex)
    }

    @Test fun `thumbnailWidth is forwarded as maxWidth to repository`() {
        val source = NetworkComicPageSource("src", "item", 10, fakeRepo, thumbnailWidth = 300, ioDispatcher = noopDispatcher)
        source.imageBytes(2)
        assertEquals(300, fakeRepo.requestedMaxWidth)
    }

    @Test fun `null thumbnailWidth passes null maxWidth to repository`() {
        val source = NetworkComicPageSource("src", "item", 10, fakeRepo, thumbnailWidth = null, ioDispatcher = noopDispatcher)
        source.imageBytes(2)
        assertEquals(null, fakeRepo.requestedMaxWidth)
    }

    @Test fun `streaming source reports a decode retry budget above one`() {
        val source = NetworkComicPageSource("src", "item", 10, fakeRepo, ioDispatcher = noopDispatcher)
        org.junit.Assert.assertTrue("network source must retry transient decode failures", source.decodeRetries > 1)
    }

    @Test fun `byte cache prevents second network request for same pageIndex`() {
        var callCount = 0
        val countingRepo = object : CbzRepository by fakeRepo {
            override suspend fun fetchStreamingPageImage(sourceId: String, itemId: String, pageIndex: Int, maxWidth: Int?): ByteArray {
                callCount++
                return fakeBytes
            }
        }
        val source = NetworkComicPageSource("src", "item", 10, countingRepo, ioDispatcher = noopDispatcher)
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

    @Test fun `accessing a page prefetches the next readAheadCount pages`() = runTest {
        val repo = RecordingRepo(fakeRepo, fakeBytes)
        val source = NetworkComicPageSource(
            "src", "item", 10, repo, readAheadCount = 2,
            ioDispatcher = StandardTestDispatcher(testScheduler),
        )
        source.imageBytes(4)
        testScheduler.advanceUntilIdle()
        assertEquals(listOf(4, 5, 6), repo.fetchedIndices.sorted())
    }

    @Test fun `prefetched page is served from cache without a new network request`() = runTest {
        val repo = RecordingRepo(fakeRepo, fakeBytes)
        val source = NetworkComicPageSource(
            "src", "item", 10, repo, readAheadCount = 2,
            ioDispatcher = StandardTestDispatcher(testScheduler),
        )
        source.imageBytes(4)
        testScheduler.advanceUntilIdle()
        repo.fetchedIndices.clear()
        source.imageBytes(5) // the page turn the user waits on — must be a cache hit
        assertEquals("page 5 must not be re-fetched after read-ahead", emptyList<Int>(), repo.fetchedIndices.filter { it == 5 })
    }

    @Test fun `read-ahead stops at the last page`() = runTest {
        val repo = RecordingRepo(fakeRepo, fakeBytes)
        val source = NetworkComicPageSource(
            "src", "item", 10, repo, readAheadCount = 2,
            ioDispatcher = StandardTestDispatcher(testScheduler),
        )
        source.imageBytes(9)
        testScheduler.advanceUntilIdle()
        assertEquals(listOf(9), repo.fetchedIndices.toList())
    }

    @Test fun `read-ahead is off by default`() {
        val repo = RecordingRepo(fakeRepo, fakeBytes)
        val source = NetworkComicPageSource("src", "item", 10, repo, ioDispatcher = noopDispatcher)
        source.imageBytes(4)
        assertEquals(listOf(4), repo.fetchedIndices.toList())
    }

    @Test fun `read-ahead does not duplicate an in-flight prefetch`() = runTest {
        val repo = RecordingRepo(fakeRepo, fakeBytes)
        val source = NetworkComicPageSource(
            "src", "item", 10, repo, readAheadCount = 2,
            ioDispatcher = StandardTestDispatcher(testScheduler),
        )
        source.imageBytes(4)
        source.imageBytes(4) // second access before the scheduler runs — must not re-enqueue 5/6
        testScheduler.advanceUntilIdle()
        assertEquals(listOf(4, 5, 6), repo.fetchedIndices.sorted())
    }

    @Test fun `a page turn joins an in-flight read-ahead instead of duplicating the download`() {
        val prefetchStarted = java.util.concurrent.CountDownLatch(1)
        val releasePrefetch = java.util.concurrent.CountDownLatch(1)
        val fetchCounts = java.util.concurrent.ConcurrentHashMap<Int, Int>()
        val gatedRepo = object : CbzRepository by fakeRepo {
            override suspend fun fetchStreamingPageImage(sourceId: String, itemId: String, pageIndex: Int, maxWidth: Int?): ByteArray {
                fetchCounts.merge(pageIndex, 1, Int::plus)
                if (pageIndex == 5) {
                    prefetchStarted.countDown()
                    releasePrefetch.await() // hold the prefetch in flight (real IO thread — blocking is fine)
                }
                return fakeBytes
            }
        }
        val source = NetworkComicPageSource(
            "src", "item", 10, gatedRepo, readAheadCount = 2, ioDispatcher = Dispatchers.IO,
        )
        source.imageBytes(4) // schedules read-ahead of 5 and 6
        org.junit.Assert.assertTrue(
            "read-ahead of page 5 never started",
            prefetchStarted.await(5, java.util.concurrent.TimeUnit.SECONDS),
        )
        Thread.sleep(50) // let the in-flight registration settle
        val turn = java.util.concurrent.Executors.newSingleThreadExecutor().submit<ByteArray> {
            source.imageBytes(5) // the page turn — must join, not re-download
        }
        releasePrefetch.countDown()
        assertArrayEquals(fakeBytes, turn.get(5, java.util.concurrent.TimeUnit.SECONDS))
        assertEquals("page 5 downloaded more than once", 1, fetchCounts[5])
    }

    @Test fun `cache retains current page alongside read-ahead entries`() = runTest {
        val repo = RecordingRepo(fakeRepo, fakeBytes)
        val source = NetworkComicPageSource(
            "src", "item", 20, repo, readAheadCount = 2,
            ioDispatcher = StandardTestDispatcher(testScheduler),
        )
        source.imageBytes(4) // decode bounds pass
        testScheduler.advanceUntilIdle() // read-ahead of 5 and 6 completes
        repo.fetchedIndices.clear()
        source.imageBytes(4) // decode pass — must still be cached after read-ahead insertions
        assertEquals(emptyList<Int>(), repo.fetchedIndices.toList())
    }
}
