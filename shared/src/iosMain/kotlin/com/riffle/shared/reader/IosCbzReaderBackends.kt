package com.riffle.shared.reader

import com.riffle.core.catalog.BookFormat
import com.riffle.core.catalog.CatalogRegistry
import com.riffle.core.common.FileStore
import com.riffle.core.domain.CbzDownloadResult
import com.riffle.core.domain.CbzLocalSource
import com.riffle.core.domain.CbzOpenResult
import com.riffle.core.domain.CbzRepository
import com.riffle.core.domain.ContentCacheAccessStore
import com.riffle.core.domain.ContentCacheArtifactKind
import com.riffle.core.domain.ContentCacheKey
import com.riffle.core.domain.LocalAvailabilityEvents
import com.riffle.core.domain.ReadingPositionStore
import com.riffle.core.domain.SourceRepository
import com.riffle.core.domain.TokenStorage
import com.riffle.core.domain.comic.ComicImageSource
import com.riffle.core.domain.comic.ComicPageSource
import com.riffle.core.models.LibraryItem
import com.riffle.core.network.KomgaCbzApi
import io.ktor.utils.io.toByteArray
import kotlinx.coroutines.CancellationException

/**
 * iOS Koin backends for the shared [com.riffle.feature.reader.CbzReaderViewModel].
 * [IosCbzRepository] is a real implementation lifted from the logic that previously lived inline
 * in [CbzReaderScreen]; since #1101 it also owns the on-disk download/cache stores.
 */

/** Wraps a plain [ComicImageSource] as a [ComicPageSource] (close is a no-op; nothing to release). */
internal fun comicPageSourceOf(src: ComicImageSource): ComicPageSource = object : ComicPageSource {
    override val pageCount: Int get() = src.pageCount
    override fun imageBytes(pageIndex: Int): ByteArray = src.imageBytes(pageIndex)
    override fun mediaType(pageIndex: Int): String = src.mediaType(pageIndex)
}

/**
 * Opens CBZ books on iOS. Resolution order mirrors Android's `CbzRepositoryImpl`: a user-pinned
 * download, then the background cache, then the network — either a full-archive download (ABS
 * ebook file, or any catalog that can stream the whole file) or per-page streaming (Komga-style
 * catalogs). A streaming session's background [awaitCachedSource] fills the cache so the reader
 * can swap to the local archive and later opens work offline (#1101 — this used to return null,
 * which left the CBZ reader streaming forever and offline reading impossible).
 *
 * Archives are held in memory once opened ([IosCbzArchive]); the on-disk copy is the offline
 * source of truth.
 */
internal class IosCbzRepository(
    private val sourceRepository: SourceRepository,
    private val tokenStorage: TokenStorage,
    private val cbzApi: KomgaCbzApi,
    private val downloader: IosCbzDownloader,
    private val positionStore: ReadingPositionStore,
    fileStore: FileStore,
    private val catalogRegistry: CatalogRegistry,
    private val contentCacheAccessStore: ContentCacheAccessStore,
    private val localAvailabilityEvents: LocalAvailabilityEvents,
) : CbzRepository {

    private val files = IosCbzFiles(fileStore)

    override suspend fun openCbz(item: LibraryItem): CbzOpenResult {
        val lastPosition = positionStore.load(item.sourceId, item.id)

        resolveLocal(item)?.let { local ->
            val archive = IosCbzArchive(local.bytes)
            return CbzOpenResult.Success(
                imageSource = comicPageSourceOf(archive),
                pageCount = archive.pageCount,
                lastPosition = lastPosition,
                bookmarks = emptyList(),
            )
        }

        // No getActive() fallback: an item belongs to exactly one source, and falling back
        // fetches from a host that does not hold it, with the wrong token (#1071 §11).
        val source = sourceRepository.getById(item.sourceId)
            ?: return CbzOpenResult.NetworkError(IllegalStateException("Source unavailable"))
        val token = tokenStorage.getToken(source.id)
            ?: return CbzOpenResult.NetworkError(IllegalStateException("No credentials"))

        return if (item.ebookFileIno != null) {
            val bytes = downloader.downloadBytes(item)
                ?: return CbzOpenResult.NetworkError(IllegalStateException("Download failed"))
            // Best effort: a failed cache write must not block reading what we just fetched.
            if (files.writeBytes(files.cachePath(item.sourceId, item.id), bytes)) {
                contentCacheAccessStore.markAccessed(contentCacheKey(item))
                localAvailabilityEvents.notifyChanged(item.sourceId, item.id)
            }
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
    ): CbzDownloadResult {
        val downloadPath = files.downloadPath(item.sourceId, item.id)
        if (files.exists(downloadPath)) return CbzDownloadResult.AlreadyDownloaded
        val cachePath = files.cachePath(item.sourceId, item.id)
        // Promote a cached copy instead of fetching it again, as Android's CatalogFileTransfer.promote does.
        if (files.exists(cachePath) && files.move(cachePath, downloadPath)) {
            localAvailabilityEvents.notifyChanged(item.sourceId, item.id)
            return CbzDownloadResult.Success
        }
        val bytes = fetchArchiveBytes(item)
            ?: return CbzDownloadResult.NetworkError(IllegalStateException("Download failed"))
        if (!files.writeBytes(downloadPath, bytes)) {
            files.delete(downloadPath)
            return CbzDownloadResult.NetworkError(IllegalStateException("Could not write $downloadPath"))
        }
        onProgress(bytes.size.toLong(), bytes.size.toLong())
        localAvailabilityEvents.notifyChanged(item.sourceId, item.id)
        return CbzDownloadResult.Success
    }

    override suspend fun removeDownload(sourceId: String, itemId: String) {
        files.delete(files.downloadPath(sourceId, itemId))
        files.delete(files.cachePath(sourceId, itemId))
        localAvailabilityEvents.notifyChanged(sourceId, itemId)
    }

    override fun isDownloaded(sourceId: String, itemId: String): Boolean =
        files.exists(files.downloadPath(sourceId, itemId))

    override fun isCached(sourceId: String, itemId: String): Boolean =
        files.exists(files.cachePath(sourceId, itemId))

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
        val source = sourceRepository.getById(sourceId)
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

    override suspend fun awaitCachedSource(item: LibraryItem): CbzLocalSource? {
        val bytes = resolveLocal(item)?.bytes ?: run {
            val fetched = fetchArchiveBytes(item) ?: return null
            val cachePath = files.cachePath(item.sourceId, item.id)
            if (!files.writeBytes(cachePath, fetched)) {
                files.delete(cachePath)
                return null
            }
            contentCacheAccessStore.markAccessed(contentCacheKey(item))
            localAvailabilityEvents.notifyChanged(item.sourceId, item.id)
            fetched
        }
        return try {
            val archive = IosCbzArchive(bytes)
            CbzLocalSource(
                imageSource = comicPageSourceOf(archive),
                pageCount = archive.pageCount,
                bookmarks = emptyList(),
            )
        } catch (_: Throwable) {
            null
        }
    }

    private class LocalArchive(val bytes: ByteArray)

    /**
     * The best valid local copy, preferring the user-pinned download over the cache. A file that
     * exists but does not parse as a ZIP is deleted so a truncated download cannot wedge the item
     * — the network path is tried instead, exactly as on Android.
     */
    private suspend fun resolveLocal(item: LibraryItem): LocalArchive? {
        val download = files.downloadPath(item.sourceId, item.id)
        if (files.exists(download)) {
            val bytes = files.readBytes(download)
            if (bytes != null && isValidCbz(bytes)) return LocalArchive(bytes)
            files.delete(download)
        }
        val cache = files.cachePath(item.sourceId, item.id)
        if (files.exists(cache)) {
            val bytes = files.readBytes(cache)
            if (bytes != null && isValidCbz(bytes)) {
                contentCacheAccessStore.markAccessed(contentCacheKey(item))
                return LocalArchive(bytes)
            }
            files.delete(cache)
        }
        return null
    }

    private fun isValidCbz(bytes: ByteArray): Boolean =
        runCatching { IosCbzArchive(bytes).pageCount > 0 }.getOrDefault(false)

    /**
     * The whole archive: ABS items through the ebook-file endpoint, everything else through the
     * item's catalog file stream (Komga serves `books/{id}/file`). Null on any failure.
     */
    private suspend fun fetchArchiveBytes(item: LibraryItem): ByteArray? {
        if (item.ebookFileIno != null) return downloader.downloadBytes(item)
        val catalog = catalogRegistry.forSourceId(item.sourceId) ?: return null
        return try {
            catalog.withFileStream(item.id, BookFormat.Cbz) { stream -> stream.channel.toByteArray() }
                .takeIf { it.isNotEmpty() }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
    }

    private fun contentCacheKey(item: LibraryItem): ContentCacheKey =
        ContentCacheKey(item.sourceId, item.id, ContentCacheArtifactKind.Cbz)
}

// IosNoOpPanelMaskService / IosNoOpPanelViewPreferencesStore / IosNoOpAppearanceCoordinator /
// IosNoOpPanelReportRepository removed (issue #1065): iOS now binds IosPanelMaskServiceImpl
// (core:data, CoreGraphics decode + shared PanelMaskBinarizer + PNG encode),
// IosPanelViewPreferencesStoreImpl (core:data), AppearanceCoordinatorImpl (core:data commonMain,
// previously Android-only), and GitHubPanelReportRepository (core:data commonMain, previously
// Android-only) via Koin.
