package com.riffle.shared.reader

import com.riffle.core.domain.LibraryObserver
import com.riffle.core.domain.ReadingPositionStore
import com.riffle.feature.reader.PdfItemResult
import com.riffle.feature.reader.PdfLocatorCodec
import com.riffle.feature.reader.SharedPdfRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * iOS implementation of [SharedPdfRepository].
 *
 * Downloads / locates the local PDF file via [IosPdfDownloader], loads the saved position from
 * [ReadingPositionStore], and exposes the resolved local path so [IosPdfReaderScreen] can hand
 * it to PDFKit.  Mirrors the pattern of Android's `AndroidPdfRepository`.
 */
internal class IosPdfSharedRepository(
    private val libraryObserver: LibraryObserver,
    private val downloader: IosPdfDownloader,
    private val positionStore: ReadingPositionStore,
) : SharedPdfRepository {

    private val _localPath = MutableStateFlow<String?>(null)
    val localPath: StateFlow<String?> = _localPath

    override suspend fun loadItem(itemId: String, sourceId: String?): PdfItemResult {
        val item = sourceId?.let { libraryObserver.getItem(it, itemId) }
            ?: libraryObserver.getItem(itemId)
            ?: return PdfItemResult.BookNotFound

        val path = downloader.localPath(item) ?: return PdfItemResult.NetworkError("Could not download PDF")
        _localPath.value = path

        val savedLocator = positionStore.load(item.sourceId, item.id)
        val initialPage = savedLocator?.let { PdfLocatorCodec.decode0Based(it) } ?: 0

        return PdfItemResult.Found(title = item.title, initialPage = initialPage)
    }

    override suspend fun saveReadingPosition(sourceId: String, itemId: String, position: String) {
        positionStore.save(sourceId, itemId, position)
    }
}
