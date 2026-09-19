package com.riffle.shared.reader

import com.riffle.core.domain.CbzDownloadResult
import com.riffle.core.domain.CbzLocalSource
import com.riffle.core.domain.CbzOpenResult
import com.riffle.core.domain.CbzRepository
import com.riffle.core.domain.ReadingPositionStore
import com.riffle.core.domain.SourceRepository
import com.riffle.core.domain.TokenStorage
import com.riffle.core.domain.comic.ComicImageSource
import com.riffle.core.domain.comic.ComicPageSource
import com.riffle.core.models.LibraryItem
import com.riffle.core.network.KomgaCbzApi

/**
 * iOS Koin backends for the shared [com.riffle.feature.reader.CbzReaderViewModel]. Most are no-ops
 * mirroring the [com.riffle.shared.library.IosNoOpStorytellerSyncer] pattern — iOS does not yet
 * persist reader preferences or sync reading sessions. [IosCbzRepository] is a real implementation
 * lifted from the logic that previously lived inline in [CbzReaderScreen].
 */

/** Wraps a plain [ComicImageSource] as a [ComicPageSource] (close is a no-op; nothing to release). */
internal fun comicPageSourceOf(src: ComicImageSource): ComicPageSource = object : ComicPageSource {
    override val pageCount: Int get() = src.pageCount
    override fun imageBytes(pageIndex: Int): ByteArray = src.imageBytes(pageIndex)
    override fun mediaType(pageIndex: Int): String = src.mediaType(pageIndex)
}

/**
 * Opens CBZ books on iOS: resolves the source + token, then either downloads the full archive
 * (ABS ebook file) or streams pages on demand (Komga-style catalogs). Progress persistence and
 * background caching are out of scope for the iOS v1 reader, so those methods are no-ops.
 */
internal class IosCbzRepository(
    private val sourceRepository: SourceRepository,
    private val tokenStorage: TokenStorage,
    private val cbzApi: KomgaCbzApi,
    private val downloader: IosCbzDownloader,
    private val positionStore: ReadingPositionStore,
) : CbzRepository {

    override suspend fun openCbz(item: LibraryItem): CbzOpenResult {
        val source = sourceRepository.getById(item.sourceId) ?: sourceRepository.getActive()
            ?: return CbzOpenResult.NetworkError(IllegalStateException("Source unavailable"))
        val token = tokenStorage.getToken(source.id)
            ?: return CbzOpenResult.NetworkError(IllegalStateException("No credentials"))

        val lastPosition = positionStore.load(item.sourceId, item.id)

        return if (item.ebookFileIno != null) {
            val bytes = downloader.downloadBytes(item)
                ?: return CbzOpenResult.NetworkError(IllegalStateException("Download failed"))
            val archive = IosCbzArchive(bytes)
            CbzOpenResult.Success(
                imageSource = comicPageSourceOf(archive),
                pageCount = archive.pageCount,
                lastPosition = lastPosition,
                bookmarks = emptyList(),
            )
        } else {
            val count = cbzApi.fetchCbzPageCount(
                baseUrl = source.url.value,
                bookId = item.id,
                token = token,
                insecureAllowed = source.insecureConnectionAllowed,
            )
            CbzOpenResult.Streaming(
                imageSource = comicPageSourceOf(
                    IosKomgaCbzImageSource(
                        api = cbzApi,
                        baseUrl = source.url.value,
                        bookId = item.id,
                        token = token,
                        insecureAllowed = source.insecureConnectionAllowed,
                        pageCount = count,
                    ),
                ),
                thumbnailSource = null,
                pageCount = count,
                lastPosition = lastPosition,
            )
        }
    }

    override suspend fun downloadCbz(
        item: LibraryItem,
        onProgress: (downloaded: Long, total: Long) -> Unit,
    ): CbzDownloadResult = CbzDownloadResult.NetworkError(UnsupportedOperationException("CBZ offline download is not supported on iOS"))

    override suspend fun removeDownload(sourceId: String, itemId: String) {}

    override fun isDownloaded(sourceId: String, itemId: String): Boolean = false

    override fun isCached(sourceId: String, itemId: String): Boolean = false

    override suspend fun saveReadingPosition(sourceId: String, itemId: String, locatorJson: String) {
        positionStore.save(sourceId, itemId, locatorJson)
    }

    override suspend fun supportsStreaming(sourceId: String): Boolean = true

    override suspend fun fetchStreamingPageImage(
        sourceId: String,
        itemId: String,
        pageIndex: Int,
        maxWidth: Int?,
    ): ByteArray {
        val source = sourceRepository.getById(sourceId) ?: sourceRepository.getActive()
            ?: throw IllegalStateException("Source unavailable")
        val token = tokenStorage.getToken(source.id)
            ?: throw IllegalStateException("No credentials")
        return cbzApi.fetchCbzPage(
            baseUrl = source.url.value,
            bookId = itemId,
            pageIndex = pageIndex,
            maxWidth = maxWidth,
            token = token,
            insecureAllowed = source.insecureConnectionAllowed,
        )
    }

    override suspend fun awaitCachedSource(item: LibraryItem): CbzLocalSource? = null
}

// IosNoOpPanelMaskService / IosNoOpPanelViewPreferencesStore / IosNoOpAppearanceCoordinator /
// IosNoOpPanelReportRepository removed (issue #1065): iOS now binds IosPanelMaskServiceImpl
// (core:data, CoreGraphics decode + shared PanelMaskBinarizer + PNG encode),
// IosPanelViewPreferencesStoreImpl (core:data), AppearanceCoordinatorImpl (core:data commonMain,
// previously Android-only), and GitHubPanelReportRepository (core:data commonMain, previously
// Android-only) via Koin.
