package com.riffle.core.data

import com.riffle.core.catalog.BookFormat
import com.riffle.core.catalog.CatalogRegistry
import com.riffle.core.catalog.CbzPageStreamCapability
import com.riffle.core.common.FileStore
import com.riffle.core.data.comic.NetworkComicPageSource
import com.riffle.core.domain.CbzDownloadResult
import com.riffle.core.domain.CbzLocalSource
import com.riffle.core.domain.CbzOpenResult
import com.riffle.core.domain.CbzRepository
import com.riffle.core.domain.ContentCacheAccessStore
import com.riffle.core.domain.ContentCacheArtifactKind
import com.riffle.core.domain.ContentCacheKey
import com.riffle.core.domain.DispatcherProvider
import com.riffle.core.domain.LocalAvailabilityEvents
import com.riffle.core.domain.ReadingPositionStore
import com.riffle.core.models.LibraryItem
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.CancellationException
import okio.FileSystem
import okio.Path.Companion.toPath

/**
 * Platform-neutral [CbzRepository] backed by [FileStore] + [FileSystem] (okio).
 *
 * Files are stored at `<namespace>/<sourceId>/<itemId>.cbz` within each namespace:
 * - Downloads: [NS_CBZ_DOWNLOADS]
 * - Cache: [NS_CBZ_CACHE]
 *
 * Archive opening is platform-specific: Android uses a file-backed [CbzArchive] (JVM, random
 * access, streams pages on demand), iOS loads the archive bytes into an [IosCbzArchive] (Kotlin/
 * Native, in-memory ZIP parser). Subclasses implement [openLocalArchive] for each platform.
 */
abstract class CbzRepositoryImpl(
    protected val fileStore: FileStore,
    protected val catalogRegistry: CatalogRegistry,
    protected val positionStore: ReadingPositionStore,
    protected val localAvailabilityEvents: LocalAvailabilityEvents = NoopLocalAvailabilityEvents,
    protected val contentCacheAccessStore: ContentCacheAccessStore = com.riffle.core.domain.NoopContentCacheAccessStore,
    protected val fileSystem: FileSystem = FileSystem.SYSTEM,
    protected val dispatchers: DispatcherProvider,
) : CbzRepository {

    protected fun downloadPath(sourceId: String, itemId: String): String =
        fileStore.resolve(NS_CBZ_DOWNLOADS, "$sourceId/$itemId.cbz")

    protected fun cachePath(sourceId: String, itemId: String): String =
        fileStore.resolve(NS_CBZ_CACHE, "$sourceId/$itemId.cbz")

    protected fun contentCacheKey(item: LibraryItem): ContentCacheKey =
        ContentCacheKey(item.sourceId, item.id, ContentCacheArtifactKind.Cbz)

    /**
     * Opens the archive at [path] (absolute path on disk) and returns [CbzOpenResult.Success], or
     * null if the file is missing, truncated, or otherwise unreadable.
     */
    protected abstract suspend fun openLocalArchive(path: String, lastPosition: String?): CbzOpenResult.Success?

    override suspend fun openCbz(item: LibraryItem): CbzOpenResult {
        val lastPosition = positionStore.load(item.sourceId, item.id)
        val dl = downloadPath(item.sourceId, item.id)
        if (fileSystem.exists(dl.toPath())) {
            openLocalArchive(dl, lastPosition)?.let { return it }
            runCatching { fileSystem.delete(dl.toPath()) }
        }
        val cache = cachePath(item.sourceId, item.id)
        if (fileSystem.exists(cache.toPath())) {
            contentCacheAccessStore.markAccessed(contentCacheKey(item))
            openLocalArchive(cache, lastPosition)?.let { return it }
            runCatching { fileSystem.delete(cache.toPath()) }
        }
        val catalog = catalogRegistry.forSourceId(item.sourceId)
            ?: return CbzOpenResult.NetworkError(IllegalStateException("No catalog for item"))

        val streamCap = catalog as? CbzPageStreamCapability
        if (streamCap != null) {
            val pageCount = try {
                streamCap.fetchCbzPageCount(item.id)
            } catch (t: Throwable) {
                return CbzOpenResult.NetworkError(t)
            }
            if (pageCount <= 0) {
                return CbzOpenResult.NetworkError(
                    IllegalStateException("Server returned zero page count for ${item.id}"),
                )
            }
            return CbzOpenResult.Streaming(
                imageSource = NetworkComicPageSource(
                    sourceId = item.sourceId,
                    itemId = item.id,
                    count = pageCount,
                    repository = this,
                    readAheadCount = 2,
                    ioDispatcher = dispatchers.io,
                ),
                thumbnailSource = NetworkComicPageSource(
                    sourceId = item.sourceId,
                    itemId = item.id,
                    count = pageCount,
                    repository = this,
                    thumbnailWidth = 300,
                    ioDispatcher = dispatchers.io,
                ),
                pageCount = pageCount,
                lastPosition = lastPosition,
            )
        }

        val destPath = cache.toPath()
        return try {
            catalog.withFileStream(item.id, BookFormat.Cbz, item.ebookFileIno) { stream ->
                fileSystem.createDirectories(destPath.parent!!)
                fileSystem.write(destPath) {
                    val buf = ByteArray(CHUNK_BYTES)
                    while (!stream.channel.isClosedForRead) {
                        val read = stream.channel.readAvailable(buf, 0, buf.size)
                        if (read <= 0) break
                        write(buf, 0, read)
                    }
                }
            }
            contentCacheAccessStore.markAccessed(contentCacheKey(item))
            localAvailabilityEvents.notifyChanged(item.sourceId, item.id)
            openLocalArchive(cache, lastPosition)
                ?: CbzOpenResult.NetworkError(IllegalStateException("Downloaded file is invalid"))
        } catch (e: CancellationException) {
            runCatching { fileSystem.delete(destPath) }
            throw e
        } catch (t: Throwable) {
            runCatching { fileSystem.delete(destPath) }
            CbzOpenResult.NetworkError(t)
        }
    }

    override suspend fun downloadCbz(
        item: LibraryItem,
        onProgress: (downloaded: Long, total: Long) -> Unit,
    ): CbzDownloadResult {
        val dlPath = downloadPath(item.sourceId, item.id)
        if (fileSystem.exists(dlPath.toPath())) return CbzDownloadResult.AlreadyDownloaded
        val cPath = cachePath(item.sourceId, item.id)
        if (fileSystem.exists(cPath.toPath())) {
            return promoteCacheToDownload(item.sourceId, item.id, cPath, dlPath, onProgress)
        }
        val catalog = catalogRegistry.forSourceId(item.sourceId)
            ?: return CbzDownloadResult.NetworkError(IllegalStateException("No catalog for item"))
        val destPath = dlPath.toPath()
        return try {
            catalog.withFileStream(item.id, BookFormat.Cbz, item.ebookFileIno) { stream ->
                val progress = CumulativeDownloadProgress(stream.contentLength, onProgress)
                fileSystem.createDirectories(destPath.parent!!)
                fileSystem.write(destPath) {
                    val buf = ByteArray(CHUNK_BYTES)
                    while (!stream.channel.isClosedForRead) {
                        val read = stream.channel.readAvailable(buf, 0, buf.size)
                        if (read <= 0) break
                        write(buf, 0, read)
                        progress.record(read.toLong())
                    }
                }
            }
            localAvailabilityEvents.notifyChanged(item.sourceId, item.id)
            CbzDownloadResult.Success
        } catch (e: CancellationException) {
            runCatching { fileSystem.delete(destPath) }
            throw e
        } catch (t: Throwable) {
            runCatching { fileSystem.delete(destPath) }
            CbzDownloadResult.NetworkError(t)
        }
    }

    private fun promoteCacheToDownload(
        sourceId: String,
        itemId: String,
        fromPath: String,
        toPath: String,
        onProgress: (Long, Long) -> Unit,
    ): CbzDownloadResult {
        val from = fromPath.toPath()
        val to = toPath.toPath()
        return try {
            to.parent?.let { fileSystem.createDirectories(it) }
            try {
                fileSystem.atomicMove(from, to)
            } catch (_: Exception) {
                fileSystem.copy(from, to)
                runCatching { fileSystem.delete(from) }
            }
            val size = runCatching { fileSystem.metadata(to).size ?: 0L }.getOrDefault(0L)
            onProgress(size, size)
            localAvailabilityEvents.notifyChanged(sourceId, itemId)
            CbzDownloadResult.Success
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            runCatching { fileSystem.delete(to) }
            CbzDownloadResult.NetworkError(t)
        }
    }

    override suspend fun removeDownload(sourceId: String, itemId: String) {
        runCatching { fileSystem.delete(downloadPath(sourceId, itemId).toPath()) }
        runCatching { fileSystem.delete(cachePath(sourceId, itemId).toPath()) }
        localAvailabilityEvents.notifyChanged(sourceId, itemId)
    }

    override fun isDownloaded(sourceId: String, itemId: String): Boolean =
        fileSystem.exists(downloadPath(sourceId, itemId).toPath())

    override fun isCached(sourceId: String, itemId: String): Boolean =
        fileSystem.exists(cachePath(sourceId, itemId).toPath())

    override suspend fun saveReadingPosition(sourceId: String, itemId: String, locatorJson: String) {
        positionStore.save(sourceId, itemId, locatorJson)
    }

    override suspend fun supportsStreaming(sourceId: String): Boolean =
        catalogRegistry.forSourceId(sourceId) is CbzPageStreamCapability

    override suspend fun fetchStreamingPageImage(
        sourceId: String,
        itemId: String,
        pageIndex: Int,
        maxWidth: Int?,
    ): ByteArray {
        val cap = catalogRegistry.forSourceId(sourceId) as? CbzPageStreamCapability
            ?: throw IllegalStateException("No CbzPageStreamCapability for source $sourceId")
        return cap.fetchCbzPageImage(itemId, pageIndex, maxWidth)
    }

    override suspend fun awaitCachedSource(item: LibraryItem): CbzLocalSource? {
        val path = resolveLocalPath(item) ?: fetchToCache(item) ?: return null
        val result = openLocalArchive(path, null) ?: return null
        return CbzLocalSource(
            imageSource = result.imageSource,
            pageCount = result.pageCount,
            bookmarks = result.bookmarks,
        )
    }

    protected suspend fun resolveLocalPath(item: LibraryItem): String? {
        val dl = downloadPath(item.sourceId, item.id)
        if (fileSystem.exists(dl.toPath())) return dl
        val cache = cachePath(item.sourceId, item.id)
        if (fileSystem.exists(cache.toPath())) {
            contentCacheAccessStore.markAccessed(contentCacheKey(item))
            return cache
        }
        return null
    }

    protected suspend fun fetchToCache(item: LibraryItem): String? {
        val catalog = catalogRegistry.forSourceId(item.sourceId) ?: return null
        val cache = cachePath(item.sourceId, item.id)
        val destPath = cache.toPath()
        return try {
            catalog.withFileStream(item.id, BookFormat.Cbz, item.ebookFileIno) { stream ->
                fileSystem.createDirectories(destPath.parent!!)
                fileSystem.write(destPath) {
                    val buf = ByteArray(CHUNK_BYTES)
                    while (!stream.channel.isClosedForRead) {
                        val read = stream.channel.readAvailable(buf, 0, buf.size)
                        if (read <= 0) break
                        write(buf, 0, read)
                    }
                }
            }
            contentCacheAccessStore.markAccessed(contentCacheKey(item))
            localAvailabilityEvents.notifyChanged(item.sourceId, item.id)
            cache
        } catch (e: CancellationException) {
            runCatching { fileSystem.delete(destPath) }
            throw e
        } catch (_: Throwable) {
            runCatching { fileSystem.delete(destPath) }
            null
        }
    }

    companion object {
        internal const val CHUNK_BYTES = 64 * 1024
    }
}
