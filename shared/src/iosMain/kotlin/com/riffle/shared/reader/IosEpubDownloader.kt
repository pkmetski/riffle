package com.riffle.shared.reader

import com.riffle.core.common.FileStore
import com.riffle.core.data.NS_EPUB_CACHE
import com.riffle.core.data.NS_EPUB_DOWNLOADS
import com.riffle.core.domain.SourceRepository
import com.riffle.core.domain.TokenStorage
import com.riffle.core.models.LibraryItem
import com.riffle.shared.library.IosEpubPaths
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsBytes
import io.ktor.http.HttpHeaders
import io.ktor.http.isSuccess
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.Foundation.NSData
import platform.Foundation.NSFileManager
import platform.Foundation.create

/**
 * Returns a local path for the given EPUB, used by the reader to open the file.
 * Priority: permanent download (epub-downloads) → cached copy (epub-cache) → download to cache.
 */
class IosEpubDownloader(
    private val httpClient: HttpClient,
    private val sourceRepository: SourceRepository,
    private val tokenStorage: TokenStorage,
    private val fileStore: FileStore,
) {
    @OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
    suspend fun localPath(item: LibraryItem): String? {
        // Prefer already-downloaded permanent copy.
        val downloadPath = fileStore.resolve(NS_EPUB_DOWNLOADS, IosEpubPaths.downloadRelativePath(item.sourceId, item.id))
        if (NSFileManager.defaultManager.fileExistsAtPath(downloadPath)) return downloadPath

        // Fall back to the cached copy.
        val cachePath = fileStore.resolve(NS_EPUB_CACHE, IosEpubPaths.cacheRelativePath(item.sourceId, item.id))
        if (NSFileManager.defaultManager.fileExistsAtPath(cachePath)) return cachePath

        // Download from ABS into the cache namespace.
        val endpoint = resolveItemEndpoint(sourceRepository, tokenStorage, item) ?: return null
        val fileIno = item.ebookFileIno ?: return null

        val urlString = endpoint.absFileUrl(item, fileIno)

        val response = runCatching {
            httpClient.get(urlString) { header(HttpHeaders.Authorization, "Bearer ${endpoint.token}") }
        }.getOrNull() ?: return null

        if (!response.status.isSuccess()) return null

        val bytes = runCatching { response.bodyAsBytes() }.getOrNull()?.takeIf { it.isNotEmpty() } ?: return null

        val nsData = bytes.usePinned { pinned ->
            NSData.create(bytes = pinned.addressOf(0), length = bytes.size.toULong())
        }

        val written = NSFileManager.defaultManager.createFileAtPath(
            path = cachePath,
            contents = nsData,
            attributes = null,
        )
        return if (written) cachePath else null
    }
}
