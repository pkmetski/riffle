package com.riffle.shared.reader

import com.riffle.core.domain.SourceRepository
import com.riffle.core.domain.TokenStorage
import com.riffle.core.models.LibraryItem
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
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.create

/**
 * Downloads PDF files from ABS to the iOS temporary directory using Ktor.
 * Uses `/api/items/{id}/file/{ino}` — the same endpoint as EPUB downloads.
 */
class IosPdfDownloader(
    private val httpClient: HttpClient,
    private val sourceRepository: SourceRepository,
    private val tokenStorage: TokenStorage,
) {
    /**
     * Returns the local file path of the PDF, downloading from ABS if not yet cached.
     * Returns null if the source, token, or file inode is unavailable, or the download fails.
     */
    @OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
    suspend fun localPath(item: LibraryItem): String? {
        val source = sourceRepository.getActive() ?: return null
        val token = tokenStorage.getToken(source.id) ?: return null
        val fileIno = item.ebookFileIno ?: return null

        val destPath = "${NSTemporaryDirectory()}riffle_pdf_${source.id}_${item.id}.pdf"
        if (NSFileManager.defaultManager.fileExistsAtPath(destPath)) return destPath

        val urlString = "${source.url.value.trimEnd('/')}/api/items/${item.id}/file/$fileIno"

        val response = runCatching {
            httpClient.get(urlString) {
                header(HttpHeaders.Authorization, "Bearer $token")
            }
        }.getOrNull() ?: return null

        if (!response.status.isSuccess()) return null

        val bytes = runCatching { response.bodyAsBytes() }.getOrNull() ?: return null
        if (bytes.isEmpty()) return null

        val nsData = bytes.usePinned { pinned ->
            NSData.create(bytes = pinned.addressOf(0), length = bytes.size.toULong())
        }

        val written = NSFileManager.defaultManager.createFileAtPath(
            path = destPath,
            contents = nsData,
            attributes = null,
        )

        return if (written) destPath else null
    }
}
