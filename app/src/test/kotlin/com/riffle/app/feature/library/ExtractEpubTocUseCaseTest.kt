package com.riffle.app.feature.library

import com.riffle.core.models.EbookFormat
import com.riffle.core.domain.EpubOpenResult
import com.riffle.core.domain.EpubRepository
import com.riffle.core.models.LibraryItem
import com.riffle.core.models.TocEntry
import com.riffle.core.domain.TocRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.readium.r2.shared.util.asset.AssetRetriever
import org.readium.r2.streamer.PublicationOpener

class ExtractEpubTocUseCaseTest {
    private val epubRepository = mockk<EpubRepository>()
    private val publicationOpener = mockk<PublicationOpener>()
    private val assetRetriever = mockk<AssetRetriever>()
    private val tocRepository = mockk<TocRepository>()
    private val useCase = ExtractEpubTocUseCase(
        epubRepository, publicationOpener, assetRetriever, tocRepository,
    )

    private fun makeItem(isCached: Boolean = true, ebookFileIno: String? = "ino1") = LibraryItem(
        id = "item1", libraryId = "lib1", title = "Book", author = "Author",
        coverUrl = null, readingProgress = 0f, isCached = isCached, isDownloaded = false,
        ebookFormat = EbookFormat.Epub, ebookFileIno = ebookFileIno, sourceId = "srv1",
    )

    @Test
    fun `returns cached entries when inode matches cache`() = runTest {
        val cached = listOf(TocEntry("Chapter 1", "ch1.html"))
        coEvery { tocRepository.getCachedToc("srv1", "item1") } returns ("ino1" to cached)

        val result = useCase(makeItem())

        assertEquals(cached, result)
        coVerify(exactly = 0) { epubRepository.openEpub(any()) }
    }

    @Test
    fun `returns empty when openEpub fails with NetworkError`() = runTest {
        coEvery { tocRepository.getCachedToc("srv1", "item1") } returns null
        coEvery { epubRepository.openEpub(any()) } returns
            EpubOpenResult.NetworkError(RuntimeException("offline"))

        val result = useCase(makeItem())

        assertTrue(result.isEmpty())
    }

    @Test
    fun `falls through with unknown sentinel when ebookFileIno is null`() = runTest {
        // Cache has no entry — extraction is attempted using "unknown" as the inode.
        coEvery { tocRepository.getCachedToc("srv1", "item1") } returns null
        coEvery { epubRepository.openEpub(any()) } returns
            EpubOpenResult.NetworkError(RuntimeException("offline"))

        val result = useCase(makeItem(ebookFileIno = null))

        // Cache was consulted (not skipped) and openEpub was called.
        coVerify(exactly = 1) { tocRepository.getCachedToc("srv1", "item1") }
        coVerify(exactly = 1) { epubRepository.openEpub(any()) }
        assertTrue(result.isEmpty())
    }

    @Test
    fun `returns cached entries when ebookFileIno is null and cache key is unknown`() = runTest {
        val cached = listOf(TocEntry("Chapter 1", "ch1.html"))
        coEvery { tocRepository.getCachedToc("srv1", "item1") } returns ("unknown" to cached)

        val result = useCase(makeItem(ebookFileIno = null))

        assertEquals(cached, result)
        coVerify(exactly = 0) { epubRepository.openEpub(any()) }
    }

    @Test
    fun `treats empty cached list as a miss and re-extracts (unknown inode)`() = runTest {
        // Regression: a transient extraction failure used to poison the cache with an empty list
        // under the "unknown" inode key (ABS < v2.36). Since the key never changes, the empty
        // list would be returned forever. The fix treats empty as a cache miss.
        coEvery { tocRepository.getCachedToc("srv1", "item1") } returns ("unknown" to emptyList())
        coEvery { epubRepository.openEpub(any()) } returns
            EpubOpenResult.NetworkError(RuntimeException("offline"))

        val result = useCase(makeItem(ebookFileIno = null))

        // The empty cache is bypassed — openEpub is called even though a cache row exists.
        coVerify(exactly = 1) { epubRepository.openEpub(any()) }
        assertTrue(result.isEmpty())
    }

    @Test
    fun `treats empty cached list as a miss and re-extracts (matching inode)`() = runTest {
        // Same regression, but for ABS >= v2.36 where a real inode is present. An empty cached
        // list must not be trusted even when the inode matches.
        coEvery { tocRepository.getCachedToc("srv1", "item1") } returns ("ino1" to emptyList())
        coEvery { epubRepository.openEpub(any()) } returns
            EpubOpenResult.NetworkError(RuntimeException("offline"))

        val result = useCase(makeItem(ebookFileIno = "ino1"))

        coVerify(exactly = 1) { epubRepository.openEpub(any()) }
        assertTrue(result.isEmpty())
    }

    @Test
    fun `ignores stale cache and re-extracts when inode does not match`() = runTest {
        val staleCached = listOf(TocEntry("Old Chapter", "old.html"))
        // Cache has inode "old-ino" but item now has "ino1"
        coEvery { tocRepository.getCachedToc("srv1", "item1") } returns ("old-ino" to staleCached)
        coEvery { epubRepository.openEpub(any()) } returns
            EpubOpenResult.NetworkError(RuntimeException("network unavailable"))

        val result = useCase(makeItem(ebookFileIno = "ino1"))

        // Stale cache is bypassed and openEpub is called
        coVerify(exactly = 1) { epubRepository.openEpub(any()) }
        // openEpub failed so result is empty (not the stale cached value)
        assertTrue(result.isEmpty())
    }
}
