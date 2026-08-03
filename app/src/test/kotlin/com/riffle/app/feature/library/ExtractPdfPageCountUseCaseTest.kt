package com.riffle.app.feature.library

import android.net.Uri
import com.riffle.core.domain.PdfOpenResult
import com.riffle.core.domain.PdfRepository
import com.riffle.core.domain.PublicationMetrics
import com.riffle.core.domain.PublicationMetricsRepository
import com.riffle.core.models.EbookFormat
import com.riffle.core.models.LibraryItem
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import java.io.File
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.readium.r2.shared.publication.Metadata
import org.readium.r2.shared.publication.Publication
import org.readium.r2.shared.util.AbsoluteUrl
import org.readium.r2.shared.util.Try
import org.readium.r2.shared.util.asset.Asset
import org.readium.r2.shared.util.asset.AssetRetriever
import org.readium.r2.streamer.PublicationOpener

class ExtractPdfPageCountUseCaseTest {

    private val pdfRepository = mockk<PdfRepository>()
    private val metricsRepository = mockk<PublicationMetricsRepository>()
    private val publicationOpener = mockk<PublicationOpener>()
    private val assetRetriever = mockk<AssetRetriever>()
    private val useCase = ExtractPdfPageCountUseCase(
        pdfRepository = pdfRepository,
        publicationOpener = publicationOpener,
        assetRetriever = assetRetriever,
        publicationMetricsRepository = metricsRepository,
    )

    private val item = LibraryItem(
        id = "pdf-1",
        sourceId = "src-1",
        libraryId = "lib-1",
        title = "PDF",
        author = "Author",
        coverUrl = null,
        readingProgress = 0f,
        isCached = true,
        isDownloaded = false,
        ebookFormat = EbookFormat.Pdf,
        ebookFileIno = "ino-1",
    )

    @Test
    fun `matching cached page count avoids reopening PDF`() = runTest {
        coEvery { metricsRepository.get("src-1", "pdf-1") } returns
            PublicationMetrics(ebookFileIno = "ino-1", pageCount = 321)

        assertEquals(321, useCase(item))
        coVerify(exactly = 0) { pdfRepository.openPdf(any()) }
    }

    @Test
    fun `page count from a replaced file is not reused`() = runTest {
        coEvery { metricsRepository.get("src-1", "pdf-1") } returns
            PublicationMetrics(ebookFileIno = "old-ino", pageCount = 321)
        coEvery { pdfRepository.openPdf(item) } returns PdfOpenResult.Offline

        assertNull(useCase(item))
        coVerify(exactly = 1) { pdfRepository.openPdf(item) }
    }

    @Test
    fun `extracts Readium page count and persists it as publication metrics`() = runTest {
        val file = File.createTempFile("riffle-pages", ".pdf").apply { deleteOnExit() }
        val asset = mockk<Asset>()
        val publication = mockk<Publication>()
        val metadata = mockk<Metadata>()
        val uri = mockk<Uri>()

        coEvery { metricsRepository.get("src-1", "pdf-1") } returns null
        coEvery { metricsRepository.save(any(), any(), any()) } returns Unit
        coEvery { pdfRepository.openPdf(item) } returns PdfOpenResult.Success(file, null)
        coEvery { assetRetriever.retrieve(any<AbsoluteUrl>()) } returns Try.Success(asset)
        coEvery {
            publicationOpener.open(asset, allowUserInteraction = false)
        } returns Try.Success(publication)
        every { publication.metadata } returns metadata
        every { metadata.numberOfPages } returns 321
        every { publication.close() } just Runs
        every { uri.isAbsolute } returns true
        every { uri.isHierarchical } returns true

        mockkStatic(Uri::class)
        try {
            every { Uri.parse(any()) } returns uri

            assertEquals(321, useCase(item))
            coVerify(exactly = 1) {
                metricsRepository.save(
                    "src-1",
                    "pdf-1",
                    PublicationMetrics(ebookFileIno = "ino-1", pageCount = 321),
                )
            }
        } finally {
            unmockkStatic(Uri::class)
        }
    }
}
