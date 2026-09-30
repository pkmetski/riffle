package com.riffle.shared.library

import com.riffle.core.catalog.BookFormat
import com.riffle.core.catalog.CatalogRegistry
import com.riffle.core.common.FileStore
import com.riffle.core.data.IosItemFiles
import com.riffle.core.data.NS_EPUB_CACHE
import com.riffle.core.data.NS_EPUB_DOWNLOADS
import com.riffle.core.domain.EpubDownloadResult
import com.riffle.core.domain.EpubRepository
import com.riffle.core.domain.ReadingPositionStore
import com.riffle.core.models.LibraryItem
import com.riffle.shared.reader.AbsFileStreamer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.CancellationException
import platform.Foundation.NSFileManager

internal class IosEpubRepositoryImpl(
    private val positionStore: ReadingPositionStore,
    private val fileStore: FileStore,
    private val absStreamer: AbsFileStreamer,
    private val catalogRegistry: CatalogRegistry,
) : EpubRepository {

    override suspend fun saveReadingPosition(sourceId: String, itemId: String, cfi: String) {
        positionStore.save(sourceId, itemId, cfi)
    }

    override suspend fun loadLastPosition(sourceId: String, itemId: String): String? =
        positionStore.load(sourceId, itemId)

    override fun isDownloaded(sourceId: String, itemId: String): Boolean =
        NSFileManager.defaultManager.fileExistsAtPath(downloadPath(sourceId, itemId))

    override fun isCached(sourceId: String, itemId: String): Boolean =
        NSFileManager.defaultManager.fileExistsAtPath(cachePath(sourceId, itemId))

    override suspend fun downloadEpub(
        item: LibraryItem,
        onProgress: (Long, Long) -> Unit,
    ): EpubDownloadResult {
        if (isDownloaded(item.sourceId, item.id)) return EpubDownloadResult.AlreadyDownloaded
        val destPath = downloadPath(item.sourceId, item.id)
        return if (item.ebookFileIno != null) {
            absStreamer.withStream(item) { channel, length ->
                if (IosItemFiles.writeChannel(destPath, channel, length, onProgress)) {
                    EpubDownloadResult.Success
                } else {
                    EpubDownloadResult.NetworkError(IllegalStateException("Failed to write EPUB to $destPath"))
                }
            } ?: EpubDownloadResult.NetworkError(IllegalStateException("ABS stream unavailable for ${item.id}"))
        } else {
            val catalog = catalogRegistry.forSourceId(item.sourceId)
                ?: return EpubDownloadResult.NetworkError(IllegalStateException("No catalog for source ${item.sourceId}"))
            try {
                catalog.withFileStream(item.id, BookFormat.Epub, null) { stream ->
                    if (IosItemFiles.writeChannel(destPath, stream.channel, stream.contentLength, onProgress)) {
                        EpubDownloadResult.Success
                    } else {
                        EpubDownloadResult.NetworkError(IllegalStateException("Failed to write EPUB to $destPath"))
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                EpubDownloadResult.NetworkError(e)
            }
        }
    }

    @OptIn(ExperimentalForeignApi::class)
    override suspend fun removeDownload(sourceId: String, itemId: String) {
        NSFileManager.defaultManager.removeItemAtPath(downloadPath(sourceId, itemId), null)
        NSFileManager.defaultManager.removeItemAtPath(cachePath(sourceId, itemId), null)
    }

    private fun downloadPath(sourceId: String, itemId: String): String =
        fileStore.resolve(NS_EPUB_DOWNLOADS, IosEpubPaths.downloadRelativePath(sourceId, itemId))

    private fun cachePath(sourceId: String, itemId: String): String =
        fileStore.resolve(NS_EPUB_CACHE, IosEpubPaths.cacheRelativePath(sourceId, itemId))
}
