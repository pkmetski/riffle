package com.riffle.shared.reader

import com.riffle.core.domain.SourceRepository
import com.riffle.core.domain.TokenStorage
import com.riffle.core.models.LibraryItem
import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpHeaders
import io.ktor.http.contentLength
import io.ktor.http.isSuccess
import io.ktor.utils.io.ByteReadChannel

/**
 * Streams a CBZ file from ABS (`/api/items/{id}/file/{ino}` — the same endpoint as EPUB
 * downloads) to a sink while the response is open, so a multi-hundred-megabyte archive never
 * has to sit in memory and progress can be reported as bytes arrive (#1101).
 */
class IosCbzDownloader(private val httpClient: HttpClient, private val sourceRepository: SourceRepository, private val tokenStorage: TokenStorage,) {
    /**
     * Opens the item's file and hands the body channel plus its declared length (−1 when the
     * server sent none) to [sink]. Returns null when the source, token or file inode is missing
     * or the request fails; otherwise [sink]'s result.
     */
    suspend fun <T> withStream(item: LibraryItem, sink: suspend (channel: ByteReadChannel, contentLength: Long) -> T): T? {
        val endpoint = resolveItemEndpoint(sourceRepository, tokenStorage, item) ?: return null
        val fileIno = item.ebookFileIno ?: return null
        val url = endpoint.absFileUrl(item, fileIno)
        return try {
            httpClient.prepareGet(url) {
                header(HttpHeaders.Authorization, "Bearer ${endpoint.token}")
            }.execute { response ->
                if (!response.status.isSuccess()) return@execute null
                sink(response.bodyAsChannel(), response.contentLength() ?: -1L)
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
    }
}
