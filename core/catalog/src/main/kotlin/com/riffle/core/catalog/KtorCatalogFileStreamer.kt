package com.riffle.core.catalog

import com.riffle.core.network.HttpRetryPolicy
import com.riffle.core.network.withHttpByteStream
import io.ktor.client.HttpClient
import java.io.IOException

data class CatalogFileRetryPolicy(
    val statusCodes: Set<Int>,
    val delaysMs: List<Long>,
) {
    companion object {
        val None = CatalogFileRetryPolicy(emptySet(), emptyList())
    }
}

data class CatalogFileHttpFailure(
    val code: Int,
    val description: String,
    val url: String,
)

class CatalogFileHttpException(failure: CatalogFileHttpFailure) : IOException(
    "HTTP ${failure.code} for ${failure.url}: ${failure.description}",
)

/**
 * Catalog-shaped adapter over the shared HTTP byte streamer. A Source supplies a resolved [handle]
 * and its exceptional policy; response lifetime, retries, and byte-stream ownership stay here.
 */
suspend fun <T> HttpClient.withCatalogFileStream(
    handle: CatalogFileHandle.Stream,
    retryPolicy: CatalogFileRetryPolicy = CatalogFileRetryPolicy.None,
    httpFailure: (CatalogFileHttpFailure) -> Throwable = ::CatalogFileHttpException,
    block: suspend (CatalogFileStream) -> T,
): T = withHttpByteStream(
    url = handle.url,
    headers = handle.headers,
    knownLength = handle.sizeBytes,
    retryPolicy = HttpRetryPolicy(retryPolicy.statusCodes, retryPolicy.delaysMs),
    httpFailure = { failure ->
        httpFailure(CatalogFileHttpFailure(failure.code, failure.description, failure.url))
    },
) { body ->
    block(
        object : CatalogFileStream {
            override val contentLength: Long = body.contentLength
            override fun byteStream() = body.inputStream
            override fun close() = body.inputStream.close()
        },
    )
}
