package com.riffle.shared.reader

import com.riffle.core.domain.CbzDownloadResult
import com.riffle.core.domain.CbzLocalSource
import com.riffle.core.domain.CbzOpenResult
import com.riffle.core.domain.CbzRepository
import com.riffle.core.domain.ReadingPositionStore
import com.riffle.core.domain.SourceRepository
import com.riffle.core.domain.TokenStorage
import com.riffle.core.domain.appearance.AppearanceCoordinator
import com.riffle.core.domain.appearance.ChromeTheme
import com.riffle.core.domain.appearance.ConcreteReaderTheme
import com.riffle.core.domain.appearance.ResolvedAppearance
import com.riffle.core.domain.comic.ComicImageSource
import com.riffle.core.domain.comic.ComicPageSource
import com.riffle.core.domain.comic.panel.PanelBinaryMask
import com.riffle.core.domain.comic.panel.PanelDetectionReport
import com.riffle.core.domain.comic.panel.PanelMaskService
import com.riffle.core.domain.comic.panel.PanelReportRepository
import com.riffle.core.domain.comic.panel.PanelViewPreferencesStore
import com.riffle.core.models.LibraryItem
import com.riffle.core.network.KomgaCbzApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf

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

internal object IosNoOpPanelMaskService : PanelMaskService {
    override suspend fun generateMask(
        pageIndex: Int,
        rawImageBytes: ByteArray,
    ): Pair<PanelBinaryMask, ByteArray>? = null
}

internal object IosNoOpPanelViewPreferencesStore : PanelViewPreferencesStore {
    override fun state(bookId: String): Flow<PanelViewPreferencesStore.State> =
        flowOf(PanelViewPreferencesStore.State())

    override suspend fun setPanelViewOn(bookId: String, on: Boolean) {}
}

internal object IosNoOpAppearanceCoordinator : AppearanceCoordinator {
    override val resolved: StateFlow<ResolvedAppearance> = MutableStateFlow(
        ResolvedAppearance(
            appChrome = ChromeTheme.Dark,
            readerTheme = ConcreteReaderTheme.Dark,
            isSystemDark = true,
        ),
    )

    override fun setSystemDark(isDark: Boolean) {}
}

internal object IosNoOpPanelReportRepository : PanelReportRepository {
    override suspend fun submit(report: PanelDetectionReport, maskPng: ByteArray): Result<String> =
        Result.failure(UnsupportedOperationException("Panel reporting is not available on iOS"))
}
