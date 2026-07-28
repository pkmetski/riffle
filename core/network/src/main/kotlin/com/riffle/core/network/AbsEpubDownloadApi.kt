package com.riffle.core.network

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpHeaders
import io.ktor.http.contentLength
import io.ktor.http.isSuccess
import io.ktor.utils.io.jvm.javaio.toInputStream
import java.io.InputStream

/** Streaming response for EPUB downloads. The caller must close [inputStream]. */
data class EpubDownloadStream(
    val inputStream: InputStream,
    val contentLength: Long,
)

/**
 * JVM-only ABS EPUB streaming surface.
 *
 * This deliberately lives in the Android-hosting `core:network` shim rather than
 * `core:net`: Ktor's `ByteReadChannel.toInputStream()` bridge is a JVM API and is
 * not part of the shared iOS-facing network contract.
 */
fun interface AbsEpubDownloadApi {
    suspend fun downloadEpub(
        baseUrl: String,
        itemId: String,
        fileIno: String,
        token: String,
        insecureAllowed: Boolean,
    ): NetworkResult<EpubDownloadStream>
}

class AbsEpubDownloadApiClient(
    private val httpClient: HttpClient,
) : AbsEpubDownloadApi {
    override suspend fun downloadEpub(
        baseUrl: String,
        itemId: String,
        fileIno: String,
        token: String,
        insecureAllowed: Boolean,
    ): NetworkResult<EpubDownloadStream> = KtorClassifier.classify {
        val client = if (insecureAllowed) httpClient.withInsecureTls() else httpClient
        val response = client.get("$baseUrl/api/items/$itemId/ebook/$fileIno") {
            header(HttpHeaders.Authorization, "Bearer $token")
        }
        if (!response.status.isSuccess()) {
            throw HttpException(response.status.value, response.status.description)
        }
        EpubDownloadStream(
            inputStream = response.bodyAsChannel().toInputStream(),
            contentLength = response.contentLength() ?: -1L,
        )
    }
}
