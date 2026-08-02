package com.riffle.core.network

import io.ktor.client.HttpClient
import io.ktor.client.plugins.timeout
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.InputStream

/**
 * A (possibly partial) byte stream of the Storyteller synced bundle.
 *
 * @param body the bundle bytes, valid only during [AudiobookBundleApi.withBundleStream].
 * @param totalBytes the full bundle size, recovered from Content-Range (206) or Content-Length (200).
 * @param isPartial true when the server honoured a Range request (206); false on a full 200 body.
 */
data class AudiobookBundleStream(val body: InputStream, val totalBytes: Long, val isPartial: Boolean)

/**
 * Opens a (resumable) byte stream of the Storyteller synced bundle — the EPUB-3-with-audio that
 * is the Readaloud audio source (see ADR 0023). [fromByte] > 0 issues a `Range` request so an
 * interrupted download can pick up where it left off.
 */
interface AudiobookBundleApi {
    suspend fun <T> withBundleStream(
        baseUrl: String,
        bookId: String,
        token: String,
        insecureAllowed: Boolean,
        fromByte: Long,
        block: suspend (AudiobookBundleStream) -> T,
    ): NetworkResult<T>
}

class AudiobookBundleApiImpl(
    private val client: HttpClient,
) : AudiobookBundleApi {

    override suspend fun <T> withBundleStream(
        baseUrl: String,
        bookId: String,
        token: String,
        insecureAllowed: Boolean,
        fromByte: Long,
        block: suspend (AudiobookBundleStream) -> T,
    ): NetworkResult<T> {
        val effectiveClient = if (insecureAllowed) client.withInsecureTls() else client
        return KtorClassifier.classify {
            val headers = buildMap {
                put(HttpHeaders.Authorization, "Bearer $token")
                put(HttpHeaders.Accept, "application/audiobook+zip")
                if (fromByte > 0L) put(HttpHeaders.Range, "bytes=$fromByte-")
            }
            effectiveClient.withHttpByteStream(
                url = "$baseUrl/api/books/$bookId/synced",
                headers = headers,
                configureRequest = {
                    // Bundle downloads can legitimately run for hours.
                    timeout {
                        connectTimeoutMillis = 30_000L
                        requestTimeoutMillis = Long.MAX_VALUE
                        socketTimeoutMillis = Long.MAX_VALUE
                    }
                },
            ) { response ->
                val isPartial = response.statusCode == HttpStatusCode.PartialContent.value
                val total = if (isPartial) {
                    response.headers[HttpHeaders.ContentRange]?.substringAfter('/')?.toLongOrNull()
                } else {
                    response.contentLength
                } ?: -1L
                currentCoroutineContext().ensureActive()
                block(
                    AudiobookBundleStream(
                        body = response.inputStream,
                        totalBytes = total,
                        isPartial = isPartial,
                    ),
                )
            }
        }
    }
}
