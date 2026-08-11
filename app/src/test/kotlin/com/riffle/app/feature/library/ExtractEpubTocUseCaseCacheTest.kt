package com.riffle.app.feature.library

import com.riffle.core.domain.EpubOpenResult
import com.riffle.core.domain.EpubRepository
import com.riffle.core.domain.PublicationMetrics
import com.riffle.core.domain.PublicationMetricsRepository
import com.riffle.core.domain.TocRepository
import com.riffle.core.models.EbookFormat
import com.riffle.core.models.LibraryItem
import com.riffle.core.models.TocEntry
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.readium.r2.shared.util.asset.AssetRetriever
import org.readium.r2.streamer.PublicationOpener

class ExtractEpubTocUseCaseCacheTest {

    private val inode = "ino-1"

    private val item = LibraryItem(
        id = "item-1", libraryId = "lib-1", title = "T", author = "A",
        coverUrl = null, readingProgress = 0f, isCached = true, isDownloaded = false,
        ebookFormat = EbookFormat.Epub, sourceId = "src-1", ebookFileIno = inode,
    )

    private val tocEntries = listOf(TocEntry("Ch 1", "ch1.html"))

    private fun makeUseCase(
        tocRepo: TocRepository,
        metricsRepo: PublicationMetricsRepository,
        epubRepo: EpubRepository = mockk(), // never called on full cache hit
    ) = ExtractEpubTocUseCase(
        epubRepository = epubRepo,
        publicationOpener = mockk<PublicationOpener>(),
        assetRetriever = mockk<AssetRetriever>(),
        tocRepository = tocRepo,
        publicationMetricsRepository = metricsRepo,
    )

    @Test
    fun `cache hit with epubVersion returns version in Details`() = runTest {
        val tocRepo = mockk<TocRepository>()
        coEvery { tocRepo.getCachedToc("src-1", "item-1") } returns (inode to tocEntries)

        val metricsRepo = mockk<PublicationMetricsRepository>()
        coEvery { metricsRepo.get("src-1", "item-1") } returns
            PublicationMetrics(ebookFileIno = inode, totalPositions = 100, epubVersion = "3.0")

        val details = makeUseCase(tocRepo, metricsRepo).extractDetails(item)

        assertEquals("3.0", details.epubVersion)
        assertEquals(tocEntries, details.tocEntries)
        assertEquals(100, details.totalPositions)
    }

    @Test
    fun `cache hit with empty-string sentinel epubVersion is a full cache hit`() = runTest {
        // An EPUB whose <package> has no version attribute stores "" as the sentinel. The guard
        // must treat "" as "already extracted" — not re-extract on every open.
        val tocRepo = mockk<TocRepository>()
        coEvery { tocRepo.getCachedToc("src-1", "item-1") } returns (inode to tocEntries)

        val metricsRepo = mockk<PublicationMetricsRepository>()
        coEvery { metricsRepo.get("src-1", "item-1") } returns
            PublicationMetrics(ebookFileIno = inode, totalPositions = 100, epubVersion = "")

        val details = makeUseCase(tocRepo, metricsRepo).extractDetails(item)

        assertEquals("", details.epubVersion)
        assertEquals(tocEntries, details.tocEntries)
    }

    @Test
    fun `cache hit with null epubVersion falls through to extraction`() = runTest {
        // Pre-migration rows have NULL epubVersion. The early-return must NOT fire so the
        // version is re-extracted on the next detail-screen open.
        val tocRepo = mockk<TocRepository>()
        coEvery { tocRepo.getCachedToc("src-1", "item-1") } returns (inode to tocEntries)

        val metricsRepo = mockk<PublicationMetricsRepository>()
        coEvery { metricsRepo.get("src-1", "item-1") } returns
            PublicationMetrics(ebookFileIno = inode, totalPositions = 100, epubVersion = null)

        val epubRepo = mockk<EpubRepository>()
        // openEpub fails (file not cached) — extraction falls through but epub wasn't accessible
        coEvery { epubRepo.openEpubForMetadata(any()) } returns
            EpubOpenResult.NetworkError(RuntimeException("unavailable"))

        val details = makeUseCase(tocRepo, metricsRepo, epubRepo).extractDetails(item)

        // epubVersion not yet available — extraction fell through but epub wasn't accessible
        assertNull(details.epubVersion)
        // cached entries are still returned as fallback
        assertEquals(tocEntries, details.tocEntries)
    }
}
