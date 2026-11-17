package com.riffle.shared.reader

import com.riffle.core.catalog.CatalogRegistry
import com.riffle.core.common.FileStore
import com.riffle.core.data.CbzRepositoryImpl
import com.riffle.core.data.NoopLocalAvailabilityEvents
import com.riffle.core.domain.CbzOpenResult
import com.riffle.core.domain.ContentCacheAccessStore
import com.riffle.core.domain.DispatcherProvider
import com.riffle.core.domain.LocalAvailabilityEvents
import com.riffle.core.domain.ReadingPositionStore
import com.riffle.core.domain.comic.ComicImageSource
import com.riffle.core.domain.comic.ComicPageSource
import okio.FileSystem
import okio.Path.Companion.toPath

/**
 * iOS extension of [CbzRepositoryImpl]: opens archives via [IosCbzArchive] (Kotlin/Native
 * in-memory ZIP parser). The download/remove/check methods are inherited from commonMain.
 */
internal class IosCbzRepositoryImpl(
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
) {

    override suspend fun openLocalArchive(path: String, lastPosition: String?): CbzOpenResult.Success? {
        val bytes = try {
            fileSystem.read(path.toPath()) { readByteArray() }
        } catch (_: Exception) {
            return null
        }
        val archive = try {
            IosCbzArchive(bytes)
        } catch (_: Exception) {
            return null
        }
        if (archive.pageCount <= 0) return null
        return CbzOpenResult.Success(
            imageSource = comicPageSourceOf(archive),
            pageCount = archive.pageCount,
            lastPosition = lastPosition,
            bookmarks = archive.readComicInfo() ?: emptyList(),
        )
    }
}

internal fun comicPageSourceOf(src: ComicImageSource): ComicPageSource = object : ComicPageSource {
    override val pageCount: Int get() = src.pageCount
    override fun imageBytes(pageIndex: Int): ByteArray = src.imageBytes(pageIndex)
    override fun mediaType(pageIndex: Int): String = src.mediaType(pageIndex)
}
