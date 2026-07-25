package com.riffle.core.network

import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.timeout
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentLength
import io.ktor.http.isSuccess
import io.ktor.utils.io.jvm.javaio.toInputStream
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.IOException
import java.io.InputStream

/**
 * A (possibly partial) byte stream of the Storyteller synced bundle.
 *
 * @param body the bundle bytes; caller must close it.
 * @param totalBytes the full bundle size, recovered from Content-Range (206) or Content-Length (200).
 * @param isPartial true when the server honoured a Range request (206); false on a full 200 body.
 */
data class AudiobookBundleStream(val body: InputStream, val totalBytes: Long, val isPartial: Boolean)

/**
 * Opens a (resumable) byte stream of the Storyteller synced bundle — the EPUB-3-with-audio that
 * is the Readaloud audio source (see ADR 0023). [fromByte] > 0 issues a `Range` request so an
 * interrupted download can pick up where it left off.
 */
fun interface AudiobookBundleApi {
    suspend fun openBundleStream(
        baseUrl: String,
        bookId: String,
        token: String,
        insecureAllowed: Boolean,
        fromByte: Long,
    ): NetworkResult<AudiobookBundleStream>
}

class AudiobookBundleApiImpl(
    private val client: HttpClient,
) : AudiobookBundleApi {

    override suspend fun openBundleStream(
        baseUrl: String,
        bookId: String,
        token: String,
        insecureAllowed: Boolean,
        fromByte: Long,
    ): NetworkResult<AudiobookBundleStream> {
        val effectiveClient = if (insecureAllowed) client.withInsecureTls() else client
        return try {
            val response = effectiveClient.get("$baseUrl/api/books/$bookId/synced") {
                header(HttpHeaders.Authorization, "Bearer $token")
                // Forward-compat: a Storyteller release that content-negotiates a Readium audiobook
                // archive will honour this; today's server ignores it and returns application/epub+zip.
                header(HttpHeaders.Accept, "application/audiobook+zip")
                if (fromByte > 0L) header(HttpHeaders.Range, "bytes=$fromByte-")
                timeout {
                    connectTimeoutMillis = 30_000L
                    requestTimeoutMillis = Long.MAX_VALUE
                    socketTimeoutMillis = Long.MAX_VALUE
                }
            }
            if (!response.status.isSuccess()) {
                response.bodyAsChannel().cancel(null)
                return NetworkResult.Offline(IOException("HTTP ${response.status.value}"))
            }
            val isPartial = response.status == HttpStatusCode.PartialContent
            val total = if (isPartial) {
                response.headers[HttpHeaders.ContentRange]?.substringAfter('/')?.toLongOrNull()
            } else {
                response.contentLength()
            } ?: -1L
            currentCoroutineContext().ensureActive()
            NetworkResult.Success(
                AudiobookBundleStream(
                    body = response.bodyAsChannel().toInputStream(),
                    totalBytes = total,
                    isPartial = isPartial,
                )
            )
        } catch (e: IOException) {
            NetworkResult.Offline(e)
        }
    }
}
