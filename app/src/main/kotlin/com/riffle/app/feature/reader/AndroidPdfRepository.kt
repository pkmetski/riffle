package com.riffle.app.feature.reader

import com.riffle.core.domain.JvmPdfRepository
import com.riffle.core.domain.LibraryObserver
import com.riffle.core.domain.PdfOpenResult
import com.riffle.feature.reader.PdfItemResult
import com.riffle.feature.reader.PdfLocatorCodec
import com.riffle.feature.reader.PdfOutlineNode
import com.riffle.feature.reader.SharedPdfRepository
import com.riffle.feature.reader.pdfOutlineToFlatRailEntries
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.readium.r2.shared.publication.Link
import org.readium.r2.shared.publication.Publication
import org.readium.r2.shared.util.AbsoluteUrl
import org.readium.r2.shared.util.Try
import org.readium.r2.shared.util.asset.AssetRetriever
import org.readium.r2.streamer.PublicationOpener
import java.io.File

/**
 * Android implementation of [SharedPdfRepository].
 *
 * Opens the PDF via Readium (JvmPdfRepository → AssetRetriever → PublicationOpener) and caches
 * the resulting [Publication] so the screen can hand it to [PdfiumNavigatorFragment]. The outline
 * and total-page-count are also cached so the screen can call
 * [com.riffle.feature.reader.PdfReaderViewModel.onNavigatorReady] immediately after the fragment
 * is created rather than waiting for a fragment callback.
 */
class AndroidPdfRepository(
    private val libraryObserver: LibraryObserver,
    private val jvmPdfRepository: JvmPdfRepository,
    private val assetRetriever: AssetRetriever,
    private val publicationOpener: PublicationOpener,
) : SharedPdfRepository {

    private val _openedState = MutableStateFlow<OpenedPdfState?>(null)
    /** Populated after a successful [loadItem]. The screen reads this to create the navigator. */
    val openedState: StateFlow<OpenedPdfState?> = _openedState

    override suspend fun loadItem(itemId: String, sourceId: String?): PdfItemResult {
        val item = sourceId?.let { libraryObserver.getItem(it, itemId) }
            ?: libraryObserver.getItem(itemId)
            ?: return PdfItemResult.BookNotFound

        return when (val result = jvmPdfRepository.openPdf(item)) {
            is PdfOpenResult.Success -> {
                val publication = openPublication(result.pdfFile).getOrElse { e ->
                    return PdfItemResult.NetworkError("Failed to open PDF: ${e.message}")
                }
                val outline = buildOutline(publication)
                val totalPages = publication.metadata.numberOfPages ?: 0
                val initialPage = result.lastPosition
                    ?.takeIf { it.isNotEmpty() }
                    ?.let { PdfLocatorCodec.decode0Based(it) }
                    ?: 0
                _openedState.value = OpenedPdfState(
                    publication = publication,
                    outline = outline,
                    totalPages = totalPages,
                )
                PdfItemResult.Found(title = item.title, initialPage = initialPage)
            }
            is PdfOpenResult.NetworkError -> PdfItemResult.NetworkError(
                result.cause.message ?: result.cause.toString(),
            )
            PdfOpenResult.Offline -> PdfItemResult.Offline
        }
    }

    override suspend fun saveReadingPosition(sourceId: String, itemId: String, position: String) {
        jvmPdfRepository.saveReadingPosition(sourceId, itemId, position)
    }

    private suspend fun openPublication(file: File): Result<Publication> {
        val url = AbsoluteUrl("file://${file.absolutePath}")
            ?: return Result.failure(IllegalArgumentException("Invalid file path: ${file.absolutePath}"))
        val asset = when (val r = assetRetriever.retrieve(url)) {
            is Try.Success -> r.value
            is Try.Failure -> return Result.failure(Exception(r.value.message))
        }
        return when (val r = publicationOpener.open(asset, allowUserInteraction = false)) {
            is Try.Success -> Result.success(r.value)
            is Try.Failure -> Result.failure(Exception(r.value.message))
        }
    }

    private fun buildOutline(publication: Publication): List<PdfOutlineNode> {
        fun pageOfLink(link: Link): Int {
            val locPos = publication.locatorFromLink(link)?.locations?.position
            if (locPos != null && locPos > 0) return locPos - 1
            val href = link.href.toString()
            val frag = href.substringAfter('#', "")
            val pageParam = frag.split('&').firstOrNull { it.startsWith("page=") }
                ?.removePrefix("page=")
                ?.toIntOrNull()
            if (pageParam != null && pageParam > 0) return pageParam - 1
            return 0
        }
        fun mapTree(links: List<Link>): List<PdfOutlineNode> = links.map { link ->
            PdfOutlineNode(
                title = link.title.orEmpty(),
                pageIndex = pageOfLink(link),
                children = mapTree(link.children),
            )
        }
        return mapTree(publication.tableOfContents)
    }

    data class OpenedPdfState(
        val publication: Publication,
        val outline: List<PdfOutlineNode>,
        val totalPages: Int,
    )
}
