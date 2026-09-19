package com.riffle.shared.library

import com.riffle.core.domain.PublicationMetrics
import com.riffle.core.domain.PublicationMetricsRepository
import com.riffle.core.models.LibraryItem
import com.riffle.feature.library.PdfPageCountExtractor
import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSURL
import platform.PDFKit.PDFDocument

/**
 * iOS [PdfPageCountExtractor]. Android's `ExtractPdfPageCountUseCase` routes through Readium's
 * AssetRetriever/PublicationOpener to read `metadata.numberOfPages`; on iOS PDFKit exposes
 * `PDFDocument.pageCount` directly, so the page count comes straight from the downloaded file.
 *
 * The caching contract is identical to Android's: a cached [PublicationMetrics] entry is reused
 * only while its `ebookFileIno` still matches the item's (so a re-uploaded file re-counts), and a
 * freshly computed count is written back through the same [PublicationMetricsRepository].
 */
@OptIn(ExperimentalForeignApi::class)
internal class IosPdfPageCountExtractor(
    /** Resolves the locally stored PDF for (sourceId, itemId) — [IosPdfRepositoryImpl.localPath]. */
    private val localPdfPath: (sourceId: String, itemId: String) -> String?,
    private val publicationMetricsRepository: PublicationMetricsRepository,
) : PdfPageCountExtractor {

    override suspend fun extract(item: LibraryItem): Int? {
        val inode = item.ebookFileIno ?: "unknown"
        publicationMetricsRepository.get(item.sourceId, item.id)
            ?.takeIf { it.ebookFileIno == inode }
            ?.pageCount
            ?.takeIf { it > 0 }
            ?.let { return it }

        // Only a locally stored PDF can be counted; the caller shows a blank count otherwise,
        // exactly as Android does when openPdfForMetadata fails.
        val path = localPdfPath(item.sourceId, item.id) ?: return null
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
