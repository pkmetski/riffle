package com.riffle.core.catalog

import com.riffle.core.network.ChannelRetryPolicy
import com.riffle.core.network.HttpChannelFailure
import com.riffle.core.network.withHttpChannelStream
import io.ktor.client.HttpClient
import io.ktor.utils.io.ByteReadChannel

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

class CatalogFileHttpException(failure: CatalogFileHttpFailure) : Exception(
    "HTTP ${failure.code} for ${failure.url}: ${failure.description}",
)

/**
 * Catalog-shaped adapter over the KMP HTTP channel streamer. A Source supplies a resolved [handle]
 * and its exceptional policy; response lifetime, retries, and channel ownership stay here.
 */
suspend fun <T> HttpClient.withCatalogFileStream(
    handle: CatalogFileHandle.Stream,
    retryPolicy: CatalogFileRetryPolicy = CatalogFileRetryPolicy.None,
    httpFailure: (CatalogFileHttpFailure) -> Throwable = ::CatalogFileHttpException,
    block: suspend (CatalogFileStream) -> T,
): T = withHttpChannelStream(
    url = handle.url,
    headers = handle.headers,
    knownLength = handle.sizeBytes,
    retryPolicy = ChannelRetryPolicy(retryPolicy.statusCodes, retryPolicy.delaysMs),
    httpFailure = { failure ->
        httpFailure(CatalogFileHttpFailure(failure.code, failure.description, failure.url))
    },
) { body ->
    block(
        object : CatalogFileStream {
            override val contentLength: Long = body.contentLength
            override val channel: ByteReadChannel = body.channel
        },
    )
}
