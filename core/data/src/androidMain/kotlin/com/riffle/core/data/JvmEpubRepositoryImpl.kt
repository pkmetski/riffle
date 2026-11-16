package com.riffle.core.data

import com.riffle.core.catalog.BookFormat
import com.riffle.core.catalog.CatalogRegistry
import com.riffle.core.common.FileStore
import com.riffle.core.domain.ContentCacheAccessStore
import com.riffle.core.domain.EpubOpenResult
import com.riffle.core.domain.JvmEpubRepository
import com.riffle.core.domain.LocalAvailabilityEvents
import com.riffle.core.domain.ReadingPositionStore
import com.riffle.core.models.LibraryItem
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.withLock
import okio.FileSystem
import okio.Path.Companion.toPath
import java.io.File

/**
 * Android-only extension of [EpubRepositoryImpl] that also implements [JvmEpubRepository].
 *
 * Adds the JVM-specific methods that require [java.io.File]: [openEpub], [openEpubForMetadata],
 * and [cacheEpub]. The download/remove/check methods are inherited from the common
 * [EpubRepositoryImpl] in commonMain.
 */
class JvmEpubRepositoryImpl(
    fileStore: FileStore,
    private val catalogRegistry: CatalogRegistry,
    positionStore: ReadingPositionStore,
    localAvailabilityEvents: LocalAvailabilityEvents = NoopLocalAvailabilityEvents,
    contentCacheAccessStore: ContentCacheAccessStore = com.riffle.core.domain.NoopContentCacheAccessStore,
    fileSystem: FileSystem = FileSystem.SYSTEM,
) : EpubRepositoryImpl(
    fileStore = fileStore,
    catalogRegistry = catalogRegistry,
    positionStore = positionStore,
    localAvailabilityEvents = localAvailabilityEvents,
    contentCacheAccessStore = contentCacheAccessStore,
    fileSystem = fileSystem,
),
    JvmEpubRepository {

    override suspend fun openEpub(item: LibraryItem): EpubOpenResult {
        val download = downloadPath(item.sourceId, item.id)
        val cache = cachePath(item.sourceId, item.id)
        val epubFile = when {
            fileSystem.exists(download.toPath()) -> File(download)
            fileSystem.exists(cache.toPath()) -> {
                contentCacheAccessStore.markAccessed(contentCacheKey(item.sourceId, item.id))
                File(cache)
            }
            else -> null
        }
        if (epubFile != null) {
            val lastPosition = positionStore.load(item.sourceId, item.id)
            return EpubOpenResult.Success(epubFile = epubFile, lastPosition = lastPosition)
        }
        val catalog = catalogRegistry.forSourceId(item.sourceId)
            ?: return EpubOpenResult.NetworkError(IllegalStateException("No catalog for item"))
        return lockFor(item.sourceId, item.id).withLock {
            val downloadAfterLock = downloadPath(item.sourceId, item.id)
            val cacheAfterLock = cachePath(item.sourceId, item.id)
            when {
                fileSystem.exists(downloadAfterLock.toPath()) -> {
                    val lastPosition = positionStore.load(item.sourceId, item.id)
                    return@withLock EpubOpenResult.Success(File(downloadAfterLock), lastPosition)
                }
                fileSystem.exists(cacheAfterLock.toPath()) -> {
                    contentCacheAccessStore.markAccessed(contentCacheKey(item.sourceId, item.id))
                    val lastPosition = positionStore.load(item.sourceId, item.id)
                    return@withLock EpubOpenResult.Success(File(cacheAfterLock), lastPosition)
                }
            }
            val destPath = cacheAfterLock.toPath()
            try {
                catalog.withFileStream(item.id, BookFormat.Epub, item.ebookFileIno) { stream ->
                    fileSystem.createDirectories(destPath.parent!!)
                    fileSystem.write(destPath) {
                        val buffer = ByteArray(CHUNK_BYTES)
                        while (true) {
                            val read = stream.channel.readAvailable(buffer, 0, buffer.size)
                            if (read <= 0) break
                            write(buffer, 0, read)
                        }
                    }
                }
                contentCacheAccessStore.markAccessed(contentCacheKey(item.sourceId, item.id))
                localAvailabilityEvents.notifyChanged(item.sourceId, item.id)
                val lastPosition = positionStore.load(item.sourceId, item.id)
                EpubOpenResult.Success(epubFile = File(cacheAfterLock), lastPosition = lastPosition)
            } catch (e: CancellationException) {
                runCatching { fileSystem.delete(destPath) }
                throw e
            } catch (t: Throwable) {
                runCatching { fileSystem.delete(destPath) }
                EpubOpenResult.NetworkError(t)
            }
        }
    }

    override suspend fun openEpubForMetadata(item: LibraryItem): EpubOpenResult {
        val download = downloadPath(item.sourceId, item.id)
        val cache = cachePath(item.sourceId, item.id)
        val local = when {
            fileSystem.exists(download.toPath()) -> File(download) to false
            fileSystem.exists(cache.toPath()) -> File(cache) to false
            else -> null
        }
        if (local != null) {
            return EpubOpenResult.Success(epubFile = local.first, lastPosition = null, temporary = false)
        }
        val catalog = catalogRegistry.forSourceId(item.sourceId)
            ?: return EpubOpenResult.NetworkError(IllegalStateException("No catalog for item"))
        return lockFor(item.sourceId, item.id).withLock {
            val dlAfterLock = downloadPath(item.sourceId, item.id)
            val cAfterLock = cachePath(item.sourceId, item.id)
            when {
                fileSystem.exists(dlAfterLock.toPath()) -> return@withLock EpubOpenResult.Success(File(dlAfterLock), null)
                fileSystem.exists(cAfterLock.toPath()) -> return@withLock EpubOpenResult.Success(File(cAfterLock), null)
            }
            val temp = java.io.File.createTempFile("riffle-metadata-", ".epub")
            try {
                catalog.withFileStream(item.id, BookFormat.Epub, item.ebookFileIno) { stream ->
                    temp.outputStream().use { output ->
                        val buffer = ByteArray(CHUNK_BYTES)
                        while (true) {
                            val read = stream.channel.readAvailable(buffer, 0, buffer.size)
                            if (read <= 0) break
                            output.write(buffer, 0, read)
                        }
                    }
                }
                EpubOpenResult.Success(epubFile = temp, lastPosition = null, temporary = true)
            } catch (e: CancellationException) {
                temp.delete()
                throw e
            } catch (t: Throwable) {
                temp.delete()
                EpubOpenResult.NetworkError(t)
            }
        }
    }

    override suspend fun cacheEpub(sourceId: String, itemId: String, bytes: ByteArray) {
        if (isDownloaded(sourceId, itemId) || isCached(sourceId, itemId)) return
        val path = cachePath(sourceId, itemId).toPath()
        fileSystem.createDirectories(path.parent!!)
        fileSystem.write(path) { write(bytes) }
        localAvailabilityEvents.notifyChanged(sourceId, itemId)
    }
}
