package com.riffle.app.feature.library

import com.riffle.core.domain.PdfOpenResult
import com.riffle.core.domain.PdfRepository
import com.riffle.core.domain.PublicationMetrics
import com.riffle.core.domain.PublicationMetricsRepository
import com.riffle.core.models.EbookFormat
import com.riffle.core.models.LibraryItem
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.readium.r2.shared.util.asset.AssetRetriever
import org.readium.r2.streamer.PublicationOpener

class ExtractPdfPageCountUseCaseTest {

    private val pdfRepository = mockk<PdfRepository>()
    private val metricsRepository = mockk<PublicationMetricsRepository>()
    private val useCase = ExtractPdfPageCountUseCase(
        pdfRepository = pdfRepository,
        publicationOpener = mockk<PublicationOpener>(),
        assetRetriever = mockk<AssetRetriever>(),
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
}
