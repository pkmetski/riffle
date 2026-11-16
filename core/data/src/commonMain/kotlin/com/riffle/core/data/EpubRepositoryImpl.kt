package com.riffle.core.data

import com.riffle.core.catalog.BookFormat
import com.riffle.core.catalog.CatalogRegistry
import com.riffle.core.common.FileStore
import com.riffle.core.domain.ContentCacheAccessStore
import com.riffle.core.domain.ContentCacheArtifactKind
import com.riffle.core.domain.ContentCacheKey
import com.riffle.core.domain.EpubDownloadResult
import com.riffle.core.domain.EpubRepository
import com.riffle.core.domain.LocalAvailabilityEvents
import com.riffle.core.domain.ReadingPositionStore
import com.riffle.core.models.LibraryItem
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okio.FileSystem
import okio.Path.Companion.toPath

/**
 * Platform-neutral [EpubRepository] backed by [FileStore] + [FileSystem] (okio).
 *
 * Files are stored at `<namespace>/<sourceId>/<itemId>.epub` within each namespace:
 * - Downloads: [NS_EPUB_DOWNLOADS]
 * - Cache: [NS_EPUB_CACHE]
 *
 * On Android, the [FileStore] is [AndroidEpubFileStore], which maps the namespace constants
 * to the directories the legacy [LocalStoreImpl] used — so on-disk paths are unchanged.
 * On iOS, the [FileStore] is [IosFileStore], rooted in the Documents directory.
 *
 * JVM-specific extensions (openEpub, openEpubForMetadata, cacheEpub) live in `JvmEpubRepositoryImpl`
 * in the androidMain source set.
 */
open class EpubRepositoryImpl(
    protected val fileStore: FileStore,
    private val catalogRegistry: CatalogRegistry,
    protected val positionStore: ReadingPositionStore,
    protected val localAvailabilityEvents: LocalAvailabilityEvents = NoopLocalAvailabilityEvents,
    protected val contentCacheAccessStore: ContentCacheAccessStore = com.riffle.core.domain.NoopContentCacheAccessStore,
    protected val fileSystem: FileSystem = FileSystem.SYSTEM,
) : EpubRepository {

    private val fetchLocks = mutableMapOf<Pair<String, String>, Mutex>()
    private val fetchLocksGuard = Mutex()

    protected suspend fun lockFor(sourceId: String, itemId: String): Mutex = fetchLocksGuard.withLock {
        fetchLocks.getOrPut(sourceId to itemId) { Mutex() }
    }

    protected fun downloadPath(sourceId: String, itemId: String): String =
        fileStore.resolve(NS_EPUB_DOWNLOADS, "$sourceId/$itemId.epub")

    protected fun cachePath(sourceId: String, itemId: String): String =
        fileStore.resolve(NS_EPUB_CACHE, "$sourceId/$itemId.epub")

    protected fun contentCacheKey(sourceId: String, itemId: String): ContentCacheKey =
        ContentCacheKey(sourceId, itemId, ContentCacheArtifactKind.Epub)

    override suspend fun downloadEpub(
        item: LibraryItem,
        onProgress: (downloaded: Long, total: Long) -> Unit,
    ): EpubDownloadResult {
        if (isDownloaded(item.sourceId, item.id)) return EpubDownloadResult.AlreadyDownloaded
        if (isCached(item.sourceId, item.id)) return promoteCacheToDownloads(item, onProgress)
        val catalog = catalogRegistry.forSourceId(item.sourceId)
            ?: return EpubDownloadResult.NetworkError(IllegalStateException("No catalog for item"))
        return lockFor(item.sourceId, item.id).withLock {
            if (isDownloaded(item.sourceId, item.id)) return@withLock EpubDownloadResult.AlreadyDownloaded
            if (isCached(item.sourceId, item.id)) return@withLock promoteCacheToDownloads(item, onProgress)
            val destPath = downloadPath(item.sourceId, item.id).toPath()
            try {
                catalog.withFileStream(item.id, BookFormat.Epub, item.ebookFileIno) { stream ->
                    val progress = CumulativeDownloadProgress(stream.contentLength, onProgress)
                    fileSystem.createDirectories(destPath.parent!!)
                    fileSystem.write(destPath) {
                        val buffer = ByteArray(CHUNK_BYTES)
                        while (!stream.channel.isClosedForRead) {
                            val read = stream.channel.readAvailable(buffer, 0, buffer.size)
                            if (read <= 0) break
                            write(buffer, 0, read)
                            progress.record(read.toLong())
                        }
                    }
                }
                localAvailabilityEvents.notifyChanged(item.sourceId, item.id)
                EpubDownloadResult.Success
            } catch (e: CancellationException) {
                runCatching { fileSystem.delete(destPath) }
                throw e
            } catch (t: Throwable) {
                runCatching { fileSystem.delete(destPath) }
                EpubDownloadResult.NetworkError(t)
            }
        }
    }

    private suspend fun promoteCacheToDownloads(
        item: LibraryItem,
        onProgress: (downloaded: Long, total: Long) -> Unit,
    ): EpubDownloadResult {
        val from = cachePath(item.sourceId, item.id).toPath()
        val to = downloadPath(item.sourceId, item.id).toPath()
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
            localAvailabilityEvents.notifyChanged(item.sourceId, item.id)
            EpubDownloadResult.Success
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            runCatching { fileSystem.delete(to) }
            EpubDownloadResult.NetworkError(t)
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

    override suspend fun saveReadingPosition(sourceId: String, itemId: String, cfi: String) {
        positionStore.save(sourceId, itemId, cfi)
    }

    override suspend fun loadLastPosition(sourceId: String, itemId: String): String? =
        positionStore.load(sourceId, itemId)

    override suspend fun loadLastPositionHref(sourceId: String, itemId: String): String? {
        val json = loadLastPosition(sourceId, itemId) ?: return null
        return runCatching {
            json.substringAfter("\"href\":\"", "").substringBefore("\"").trimStart('/').takeIf { it.isNotEmpty() }
        }.getOrNull()
    }

    companion object {
        internal const val CHUNK_BYTES = 64 * 1024
    }
}
