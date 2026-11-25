package com.riffle.core.data

import com.riffle.core.catalog.CatalogRegistry
import com.riffle.core.common.FileStore
import com.riffle.core.domain.CbzLocalSource
import com.riffle.core.domain.CbzOpenResult
import com.riffle.core.domain.ContentCacheAccessStore
import com.riffle.core.domain.DispatcherProvider
import com.riffle.core.domain.JvmCbzRepository
import com.riffle.core.domain.LocalAvailabilityEvents
import com.riffle.core.domain.ReadingPositionStore
import com.riffle.core.domain.comic.CbzArchive
import com.riffle.core.domain.comic.ComicArchivePageSource
import com.riffle.core.models.LibraryItem
import kotlinx.coroutines.withContext
import okio.FileSystem
import java.io.File
import java.io.IOException
import java.util.zip.ZipException
import java.util.zip.ZipFile

/**
 * Android-only extension of [CbzRepositoryImpl] that also implements [JvmCbzRepository].
 *
 * Adds the JVM-specific methods that require [java.io.File]: [awaitCachedFile]. The download/
 * remove/check methods are inherited from the common [CbzRepositoryImpl] in commonMain.
 *
 * Archive opening uses [CbzArchive], which is backed by [ZipFile] and streams page images on
 * demand without loading the entire archive into memory — suitable for large comic archives.
 */
class JvmCbzRepositoryImpl(
    fileStore: FileStore,
    catalogRegistry: CatalogRegistry,
    positionStore: ReadingPositionStore,
    localAvailabilityEvents: LocalAvailabilityEvents = NoopLocalAvailabilityEvents,
    contentCacheAccessStore: ContentCacheAccessStore = com.riffle.core.domain.NoopContentCacheAccessStore,
    fileSystem: FileSystem = FileSystem.SYSTEM,
    dispatchers: DispatcherProvider,
) : CbzRepositoryImpl(
    fileStore = fileStore,
    catalogRegistry = catalogRegistry,
    positionStore = positionStore,
    localAvailabilityEvents = localAvailabilityEvents,
    contentCacheAccessStore = contentCacheAccessStore,
    fileSystem = fileSystem,
    dispatchers = dispatchers,
),
    JvmCbzRepository {

    override suspend fun openLocalArchive(path: String, lastPosition: String?): CbzOpenResult.Success? =
        withContext(dispatchers.io) {
            val file = File(path)
            if (!file.isValidCbz()) return@withContext null
            try {
                val archive = CbzArchive(file)
                CbzOpenResult.Success(
                    imageSource = ComicArchivePageSource(archive),
                    pageCount = archive.pageCount,
                    lastPosition = lastPosition,
                    bookmarks = archive.readComicInfo() ?: emptyList(),
                )
            } catch (_: Throwable) {
                null
            }
        }

    override suspend fun awaitCachedFile(item: LibraryItem): File? {
        val path = resolveLocalPath(item) ?: fetchToCache(item) ?: return null
        val file = File(path)
        return if (file.isValidCbz()) file else null
    }

    override suspend fun awaitCachedSource(item: LibraryItem): CbzLocalSource? {
        val file = awaitCachedFile(item) ?: return null
        return withContext(dispatchers.io) {
            try {
                val archive = CbzArchive(file)
                CbzLocalSource(
                    imageSource = ComicArchivePageSource(archive),
                    pageCount = archive.pageCount,
                    bookmarks = archive.readComicInfo() ?: emptyList(),
                )
            } catch (_: Throwable) {
                null
            }
        }
    }
}

private fun File.isValidCbz(): Boolean {
    if (!exists() || length() < 22) return false
    return try {
        ZipFile(this).use { true }
    } catch (_: ZipException) {
        false
    } catch (_: IOException) {
        false
    }
}
