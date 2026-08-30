package com.riffle.app.feature.library

import com.riffle.core.domain.PdfOpenResult
import com.riffle.core.domain.PdfRepository
import com.riffle.core.domain.PublicationMetrics
import com.riffle.core.domain.PublicationMetricsRepository
import com.riffle.core.models.LibraryItem
import org.readium.r2.shared.util.AbsoluteUrl
import org.readium.r2.shared.util.Try
import org.readium.r2.shared.util.asset.AssetRetriever
import org.readium.r2.shared.util.use
import org.readium.r2.streamer.PublicationOpener

class ExtractPdfPageCountUseCase constructor(
    private val pdfRepository: PdfRepository,
    private val publicationOpener: PublicationOpener,
    private val assetRetriever: AssetRetriever,
    private val publicationMetricsRepository: PublicationMetricsRepository,
) {
    suspend operator fun invoke(item: LibraryItem): Int? {
        val inode = item.ebookFileIno ?: "unknown"
        val cached = publicationMetricsRepository.get(item.sourceId, item.id)
        cached
            ?.takeIf { it.ebookFileIno == inode }
            ?.pageCount
            ?.takeIf { it > 0 }
            ?.let { return it }

        val opened = when (val result = pdfRepository.openPdfForMetadata(item)) {
            is PdfOpenResult.Success -> result
            else -> return null
        }
        val file = opened.pdfFile
        try {
            val url = AbsoluteUrl("file://${file.absolutePath}") ?: return null
            val asset = when (val result = assetRetriever.retrieve(url)) {
                is Try.Success -> result.value
                is Try.Failure -> return null
            }
            val publication = when (
                val result = publicationOpener.open(asset, allowUserInteraction = false)
            ) {
                is Try.Success -> result.value
                is Try.Failure -> return null
            }

            return publication.use {
                val pageCount = it.metadata.numberOfPages?.takeIf { count -> count > 0 } ?: return@use null
                publicationMetricsRepository.save(
                    item.sourceId,
                    item.id,
                    PublicationMetrics(
                        ebookFileIno = inode,
                        pageCount = pageCount,
                    ),
                )
                pageCount
            }
        } finally {
            if (opened.temporary) file.delete()
        }
    }
}
