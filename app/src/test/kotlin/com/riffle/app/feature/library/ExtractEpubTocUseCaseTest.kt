package com.riffle.app.feature.library

import android.net.Uri
import com.riffle.core.domain.EpubOpenResult
import com.riffle.core.domain.EpubRepository
import com.riffle.core.domain.PublicationMetrics
import com.riffle.core.domain.PublicationMetricsRepository
import com.riffle.core.domain.TocRepository
import com.riffle.core.models.EbookFormat
import com.riffle.core.models.LibraryItem
import com.riffle.core.models.TocEntry
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import io.mockk.verify
import java.io.File
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.readium.r2.shared.publication.Layout
import org.readium.r2.shared.publication.Href
import org.readium.r2.shared.publication.Link
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.publication.Publication
import org.readium.r2.shared.publication.services.PositionsService
import org.readium.r2.shared.util.AbsoluteUrl
import org.readium.r2.shared.util.Try
import org.readium.r2.shared.util.asset.Asset
import org.readium.r2.shared.util.asset.AssetRetriever
import org.readium.r2.streamer.PublicationOpener

class ExtractEpubTocUseCaseTest {
    private val epubRepository = mockk<EpubRepository>()
    private val publicationOpener = mockk<PublicationOpener>()
    private val assetRetriever = mockk<AssetRetriever>()
    private val tocRepository = mockk<TocRepository>()
    private val publicationMetricsRepository = mockk<PublicationMetricsRepository>()
    private val useCase = ExtractEpubTocUseCase(
        epubRepository,
        publicationOpener,
        assetRetriever,
        tocRepository,
        publicationMetricsRepository,
    )

    private fun makeItem(isCached: Boolean = true, ebookFileIno: String? = "ino1") = LibraryItem(
        id = "item1", libraryId = "lib1", title = "Book", author = "Author",
        coverUrl = null, readingProgress = 0f, isCached = isCached, isDownloaded = false,
        ebookFormat = EbookFormat.Epub, ebookFileIno = ebookFileIno, sourceId = "srv1",
    )

    private fun storedEpub(vararg entries: Pair<String, Int>): File =
        File.createTempFile("riffle-positions", ".epub").apply {
            deleteOnExit()
            ZipOutputStream(outputStream()).use { archive ->
                entries.forEach { (name, size) ->
                    val bytes = ByteArray(size) { index -> (index % 251).toByte() }
                    val crc = CRC32().apply { update(bytes) }
                    val entry = ZipEntry(name).apply {
                        method = ZipEntry.STORED
                        this.size = bytes.size.toLong()
                        compressedSize = bytes.size.toLong()
                        this.crc = crc.value
                    }
                    archive.putNextEntry(entry)
                    archive.write(bytes)
                    archive.closeEntry()
                }
            }
        }

    @Test
    fun `position count matches Readium stored entry length strategy`() {
        val file = storedEpub(
            "OPS/first.xhtml" to 1025,
            "OPS/second.xhtml" to 0,
            "OPS/Chapter One.xhtml" to 2048,
        )

        assertEquals(
            5,
            countEpubPositionsFromArchive(
                epubFile = file,
                readingOrderHrefs = listOf(
                    "OPS/first.xhtml",
                    "OPS/second.xhtml",
                    "OPS/Chapter%20One.xhtml#section",
                ),
                layout = null,
            ),
        )
    }

    @Test
    fun `fixed layout counts one position per reading order resource`() {
        assertEquals(
            7,
            countEpubPositionsFromArchive(
                epubFile = File("not-opened-for-fixed-layout.epub"),
                readingOrderHrefs = List(7) { "page-$it.xhtml" },
                layout = Layout.FIXED,
            ),
        )
    }

    @Test
    fun `returns cached entries when inode matches cache`() = runTest {
        val cached = listOf(TocEntry("Chapter 1", "ch1.html"))
        coEvery { tocRepository.getCachedToc("srv1", "item1") } returns ("ino1" to cached)
        coEvery { publicationMetricsRepository.get("srv1", "item1") } returns
            PublicationMetrics("ino1", totalPositions = 120, epubVersion = "3.0")

        val result = useCase.extractDetails(makeItem())

        assertEquals(cached, result.tocEntries)
        assertEquals(120, result.totalPositions)
        coVerify(exactly = 0) { epubRepository.openEpubForMetadata(any()) }
    }

    @Test
    fun `returns empty when openEpub fails with NetworkError`() = runTest {
        coEvery { tocRepository.getCachedToc("srv1", "item1") } returns null
        coEvery { publicationMetricsRepository.get("srv1", "item1") } returns null
        coEvery { epubRepository.openEpubForMetadata(any()) } returns
            EpubOpenResult.NetworkError(RuntimeException("offline"))

        val result = useCase(makeItem())

        assertTrue(result.isEmpty())
    }

    @Test
    fun `falls through with unknown sentinel when ebookFileIno is null`() = runTest {
        // Cache has no entry — extraction is attempted using "unknown" as the inode.
        coEvery { tocRepository.getCachedToc("srv1", "item1") } returns null
        coEvery { publicationMetricsRepository.get("srv1", "item1") } returns null
        coEvery { epubRepository.openEpubForMetadata(any()) } returns
            EpubOpenResult.NetworkError(RuntimeException("offline"))

        val result = useCase(makeItem(ebookFileIno = null))

        // Cache was consulted (not skipped) and openEpub was called.
        coVerify(exactly = 1) { tocRepository.getCachedToc("srv1", "item1") }
        coVerify(exactly = 1) { epubRepository.openEpubForMetadata(any()) }
        assertTrue(result.isEmpty())
    }

    @Test
    fun `returns cached entries when ebookFileIno is null and cache key is unknown`() = runTest {
        val cached = listOf(TocEntry("Chapter 1", "ch1.html"))
        coEvery { tocRepository.getCachedToc("srv1", "item1") } returns ("unknown" to cached)
        coEvery { publicationMetricsRepository.get("srv1", "item1") } returns
            PublicationMetrics("unknown", totalPositions = 120, epubVersion = "3.0")

        val result = useCase(makeItem(ebookFileIno = null))

        assertEquals(cached, result)
        coVerify(exactly = 0) { epubRepository.openEpubForMetadata(any()) }
    }

    @Test
    fun `treats empty cached list as a miss and re-extracts (unknown inode)`() = runTest {
        // Regression: a transient extraction failure used to poison the cache with an empty list
        // under the "unknown" inode key (ABS < v2.36). Since the key never changes, the empty
        // list would be returned forever. The fix treats empty as a cache miss.
        coEvery { tocRepository.getCachedToc("srv1", "item1") } returns ("unknown" to emptyList())
        coEvery { publicationMetricsRepository.get("srv1", "item1") } returns
            PublicationMetrics("unknown", totalPositions = 120)
        coEvery { epubRepository.openEpubForMetadata(any()) } returns
            EpubOpenResult.NetworkError(RuntimeException("offline"))

        val result = useCase(makeItem(ebookFileIno = null))

        // The empty cache is bypassed — openEpub is called even though a cache row exists.
        coVerify(exactly = 1) { epubRepository.openEpubForMetadata(any()) }
        assertTrue(result.isEmpty())
    }

    @Test
    fun `treats empty cached list as a miss and re-extracts (matching inode)`() = runTest {
        // Same regression, but for ABS >= v2.36 where a real inode is present. An empty cached
        // list must not be trusted even when the inode matches.
        coEvery { tocRepository.getCachedToc("srv1", "item1") } returns ("ino1" to emptyList())
        coEvery { publicationMetricsRepository.get("srv1", "item1") } returns
            PublicationMetrics("ino1", totalPositions = 120)
        coEvery { epubRepository.openEpubForMetadata(any()) } returns
            EpubOpenResult.NetworkError(RuntimeException("offline"))

        val result = useCase(makeItem(ebookFileIno = "ino1"))

        coVerify(exactly = 1) { epubRepository.openEpubForMetadata(any()) }
        assertTrue(result.isEmpty())
    }

    @Test
    fun `ignores stale cache and re-extracts when inode does not match`() = runTest {
        val staleCached = listOf(TocEntry("Old Chapter", "old.html"))
        // Cache has inode "old-ino" but item now has "ino1"
        coEvery { tocRepository.getCachedToc("srv1", "item1") } returns ("old-ino" to staleCached)
        coEvery { publicationMetricsRepository.get("srv1", "item1") } returns
            PublicationMetrics("ino1", totalPositions = 120)
        coEvery { epubRepository.openEpubForMetadata(any()) } returns
            EpubOpenResult.NetworkError(RuntimeException("network unavailable"))

        val result = useCase(makeItem(ebookFileIno = "ino1"))

        // Stale cache is bypassed and openEpub is called
        coVerify(exactly = 1) { epubRepository.openEpubForMetadata(any()) }
        // openEpub failed so result is empty (not the stale cached value)
        assertTrue(result.isEmpty())
    }

    @Test
    fun `counts archive entries without materializing Readium positions and persists metrics`() = runTest {
        val file = storedEpub(
            "OPS/first.xhtml" to 80 * 1024,
            "OPS/second.xhtml" to 40 * 1024,
        )
        val asset = mockk<Asset>()
        val publication = mockk<Publication>()
        val firstLink = mockk<Link>()
        val secondLink = mockk<Link>()
        val firstHref = mockk<Href>()
        val secondHref = mockk<Href>()
        val locator = mockk<Locator>()
        val positionsService = mockk<PositionsService>()
        val uri = mockk<Uri>()

        coEvery { tocRepository.getCachedToc("srv1", "item1") } returns null
        coEvery { publicationMetricsRepository.get("srv1", "item1") } returns null
        coEvery { publicationMetricsRepository.save(any(), any(), any()) } returns Unit
        coEvery { epubRepository.openEpubForMetadata(any()) } returns
            EpubOpenResult.Success(file, null, temporary = true)
        coEvery { assetRetriever.retrieve(any<AbsoluteUrl>()) } returns Try.Success(asset)
        coEvery {
            publicationOpener.open(asset, allowUserInteraction = false)
        } returns Try.Success(publication)
        coEvery { positionsService.positionsByReadingOrder() } returns listOf(
            List(80) { locator },
            List(40) { locator },
        )
        every { publication.tableOfContents } returns emptyList()
        every { publication.metadata.layout } returns null
        every { publication.readingOrder } returns listOf(firstLink, secondLink)
        every { firstLink.href } returns firstHref
        every { secondLink.href } returns secondHref
        every { firstHref.toString() } returns "OPS/first.xhtml"
        every { secondHref.toString() } returns "OPS/second.xhtml"
        every { publication.findService(PositionsService::class) } returns positionsService
        every { publication.close() } just Runs
        every { uri.isAbsolute } returns true
        every { uri.isHierarchical } returns true

        mockkStatic(Uri::class)
        try {
            every { Uri.parse(any()) } returns uri

            val result = useCase.extractDetails(makeItem())

            assertEquals(120, result.totalPositions)
            coVerify(exactly = 1) {
                publicationMetricsRepository.save(
                    "srv1",
                    "item1",
                    // "" sentinel: the test EPUB has no valid OPF so extractor returns EMPTY;
                    // the sentinel prevents infinite re-extraction for version-less EPUBs.
                    PublicationMetrics(ebookFileIno = "ino1", totalPositions = 120, epubVersion = ""),
                )
            }
            coVerifyOrder {
                publication.close()
                publicationMetricsRepository.save(
                    "srv1",
                    "item1",
                    PublicationMetrics(ebookFileIno = "ino1", totalPositions = 120, epubVersion = ""),
                )
            }
            coVerify(exactly = 0) { positionsService.positionsByReadingOrder() }
            verify(exactly = 0) { publication.get(any<Link>()) }
            assertFalse(file.exists())
        } finally {
            unmockkStatic(Uri::class)
        }
    }
}
