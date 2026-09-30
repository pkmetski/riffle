package com.riffle.shared.library

import com.riffle.core.common.FileStore
import com.riffle.core.data.IosItemFiles
import com.riffle.core.data.NS_EPUB_CACHE
import com.riffle.core.data.NS_EPUB_DOWNLOADS
import com.riffle.core.domain.EpubDownloadResult
import com.riffle.core.domain.EpubRepository
import com.riffle.core.domain.ReadingPositionStore
import com.riffle.core.domain.SourceRepository
import com.riffle.core.domain.TokenStorage
import com.riffle.core.models.LibraryItem
import com.riffle.core.network.withHttpChannelStream
import io.ktor.client.HttpClient
import io.ktor.http.HttpHeaders
import platform.Foundation.NSFileManager

internal class IosEpubRepositoryImpl(
    private val positionStore: ReadingPositionStore,
    private val fileStore: FileStore,
    private val sourceRepository: SourceRepository,
    private val tokenStorage: TokenStorage,
    private val httpClient: HttpClient,
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

        // The item's own source, not whichever is active — see ItemSourceEndpoint (#1071 §11).
        val source = sourceRepository.getById(item.sourceId) ?: return EpubDownloadResult.NetworkError(
            IllegalStateException("Source unavailable for item ${item.id}")
        )
        val token = tokenStorage.getToken(source.id) ?: return EpubDownloadResult.NetworkError(
            IllegalStateException("No auth token for source ${source.id}")
        )
        val fileIno = item.ebookFileIno ?: return EpubDownloadResult.NetworkError(
            IllegalStateException("Item ${item.id} has no ebookFileIno")
        )

        val urlString = "${source.url.value.trimEnd('/')}/api/items/${item.id}/file/$fileIno"
        val destPath = downloadPath(item.sourceId, item.id)

        return runCatching {
            httpClient.withHttpChannelStream(
                url = urlString,
                headers = mapOf(HttpHeaders.Authorization to "Bearer $token"),
            ) { stream ->
                // IosItemFiles.writeChannel creates the $sourceId/ parent directory before
                // writing, which NSFileManager.createFileAtPath does not.
                val written = IosItemFiles.writeChannel(destPath, stream.channel, stream.contentLength, onProgress)
                if (written) {
                    EpubDownloadResult.Success
                } else {
                    EpubDownloadResult.NetworkError(IllegalStateException("Failed to write file to $destPath"))
                }
            }
        }.getOrElse { EpubDownloadResult.NetworkError(it) }
    }

    @OptIn(ExperimentalForeignApi::class)
    override suspend fun removeDownload(sourceId: String, itemId: String) {
        NSFileManager.defaultManager.removeItemAtPath(downloadPath(sourceId, itemId), null)
        // Also remove the cache copy so isCached() stays consistent.
        NSFileManager.defaultManager.removeItemAtPath(cachePath(sourceId, itemId), null)
    }

    private fun downloadPath(sourceId: String, itemId: String): String =
        fileStore.resolve(NS_EPUB_DOWNLOADS, IosEpubPaths.downloadRelativePath(sourceId, itemId))

    private fun cachePath(sourceId: String, itemId: String): String =
        fileStore.resolve(NS_EPUB_CACHE, IosEpubPaths.cacheRelativePath(sourceId, itemId))
}
