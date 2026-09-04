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

/**
 * Downloads a CBZ file from ABS and returns its raw bytes.
 * Uses `/api/items/{id}/file/{ino}` — same endpoint as EPUB downloads.
 */
class IosCbzDownloader(
    private val httpClient: HttpClient,
    private val sourceRepository: SourceRepository,
    private val tokenStorage: TokenStorage,
) {
    suspend fun downloadBytes(item: LibraryItem): ByteArray? {
        val source = sourceRepository.getActive() ?: return null
        val token = tokenStorage.getToken(source.id) ?: return null
        val fileIno = item.ebookFileIno ?: return null

        val url = "${source.url.value.trimEnd('/')}/api/items/${item.id}/file/$fileIno"
        val response = runCatching {
            httpClient.get(url) {
                header(HttpHeaders.Authorization, "Bearer $token")
            }
        }.getOrNull() ?: return null

        if (!response.status.isSuccess()) return null
        return runCatching { response.bodyAsBytes() }.getOrNull()
            ?.takeIf { it.isNotEmpty() }
    }
}
