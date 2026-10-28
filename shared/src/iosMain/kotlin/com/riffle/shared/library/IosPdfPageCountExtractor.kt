package com.riffle.shared.library

import com.riffle.core.domain.PublicationMetrics
import com.riffle.core.domain.PublicationMetricsRepository
import com.riffle.core.models.LibraryItem
import com.riffle.feature.library.PdfPageCountExtractor
import com.riffle.shared.reader.IosPdfDownloader
import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSURL
import platform.PDFKit.PDFDocument

/**
 * iOS [PdfPageCountExtractor]. Android's `ExtractPdfPageCountUseCase` routes through Readium's
 * AssetRetriever/PublicationOpener to read `metadata.numberOfPages`; on iOS PDFKit exposes
 * `PDFDocument.pageCount` directly.
 *
 * Page count is extracted from the locally stored PDF when available, or fetched via
 * [IosPdfDownloader] for remote-only items — mirroring Android's `openPdfForMetadata` path
 * which downloads a temporary copy when the file is not already cached.
 *
 * The caching contract is identical to Android's: a cached [PublicationMetrics] entry is reused
 * only while its `ebookFileIno` still matches the item's (so a re-uploaded file re-counts), and a
 * freshly computed count is written back through the same [PublicationMetricsRepository].
 */
@OptIn(ExperimentalForeignApi::class)
internal class IosPdfPageCountExtractor(
    /** Resolves (or downloads) the PDF for the item — [IosPdfDownloader.localPath]. */
    private val downloader: IosPdfDownloader,
    private val publicationMetricsRepository: PublicationMetricsRepository,
) : PdfPageCountExtractor {

    override suspend fun extract(item: LibraryItem): Int? {
        val inode = item.ebookFileIno ?: "unknown"
        publicationMetricsRepository.get(item.sourceId, item.id)
            ?.takeIf { it.ebookFileIno == inode }
            ?.pageCount
            ?.takeIf { it > 0 }
            ?.let { return it }

        val path = downloader.localPath(item) ?: return null
        val document = PDFDocument(NSURL.fileURLWithPath(path)) ?: return null
        val pageCount = document.pageCount.toInt().takeIf { it > 0 } ?: return null

        publicationMetricsRepository.save(
            item.sourceId,
            item.id,
            PublicationMetrics(ebookFileIno = inode, pageCount = pageCount),
        )
        return pageCount
    }
}
