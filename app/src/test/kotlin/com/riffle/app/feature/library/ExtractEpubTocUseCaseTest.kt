package com.riffle.app.feature.library

import com.riffle.core.domain.EbookFormat
import com.riffle.core.domain.EpubOpenResult
import com.riffle.core.domain.EpubRepository
import com.riffle.core.domain.LibraryItem
import com.riffle.core.domain.TocEntry
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
        ebookFormat = EbookFormat.Epub, ebookFileIno = ebookFileIno, serverId = "srv1",
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
    fun `returns empty when item has no ebookFileIno`() = runTest {
        val result = useCase(makeItem(ebookFileIno = null))
        assertTrue(result.isEmpty())
        coVerify(exactly = 0) { tocRepository.getCachedToc(any(), any()) }
    }
}
