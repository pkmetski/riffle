package com.riffle.core.network

import io.ktor.client.HttpClient
import io.ktor.http.HttpHeaders

/** Live response body for any single-file ABS book format. */
typealias AbsFileStream = HttpByteStream

/**
 * JVM-only ABS single-file book streaming surface.
 *
 * This deliberately lives in the Android-hosting `core:network` shim rather than
 * `core:net`: Ktor's `ByteReadChannel.toInputStream()` bridge is a JVM API and is
 * not part of the shared iOS-facing network contract.
 */
interface AbsFileDownloadApi {
    /** Runs [block] against the live response body rather than a fully buffered replay. */
    suspend fun <T> streamFile(
        baseUrl: String,
        itemId: String,
        fileIno: String,
        token: String,
        insecureAllowed: Boolean,
        block: suspend (AbsFileStream) -> T,
    ): NetworkResult<T>
}

class AbsFileDownloadApiClient(
    private val httpClient: HttpClient,
) : AbsFileDownloadApi {
    override suspend fun <T> streamFile(
        baseUrl: String,
        itemId: String,
        fileIno: String,
        token: String,
        insecureAllowed: Boolean,
        block: suspend (AbsFileStream) -> T,
    ): NetworkResult<T> = KtorClassifier.classify {
        val client = if (insecureAllowed) httpClient.withInsecureTls() else httpClient
        client.withHttpByteStream(
            url = "$baseUrl/api/items/$itemId/ebook/$fileIno",
            headers = mapOf(HttpHeaders.Authorization to "Bearer $token"),
            httpFailure = { failure -> HttpException(failure.code, failure.description) },
            block = block,
        )
    }
}
