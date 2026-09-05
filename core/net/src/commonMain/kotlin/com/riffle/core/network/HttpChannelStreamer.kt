package com.riffle.core.network

import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.Headers
import io.ktor.http.contentLength
import io.ktor.http.isSuccess
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.delay

data class HttpChannelStream(
    val channel: ByteReadChannel,
    val contentLength: Long,
    val statusCode: Int,
    val headers: Headers,
)

data class ChannelRetryPolicy(val statusCodes: Set<Int>, val delaysMs: List<Long>) {
    companion object {
        val None = ChannelRetryPolicy(emptySet(), emptyList())
    }
}

data class HttpChannelFailure(val code: Int, val description: String, val url: String)

class HttpChannelException(val failure: HttpChannelFailure) : Exception(
    "HTTP ${failure.code} for ${failure.url}: ${failure.description}",
)

/**
 * KMP-friendly streaming helper. Runs [block] while a GET response is open, exposing bytes via
 * [ByteReadChannel]. Unlike [withHttpByteStream] (JVM-only, InputStream-based), this function
 * works on all KMP targets and is the right choice for catalog modules.
 */
suspend fun <T> HttpClient.withHttpChannelStream(
    url: String,
    headers: Map<String, String> = emptyMap(),
    knownLength: Long? = null,
    retryPolicy: ChannelRetryPolicy = ChannelRetryPolicy.None,
    httpFailure: (HttpChannelFailure) -> Throwable = ::HttpChannelException,
    block: suspend (HttpChannelStream) -> T,
): T {
    var attempt = 0
    while (true) {
        try {
            return prepareGet(url) {
                headers.forEach { (name, value) -> header(name, value) }
            }.execute { response ->
                if (!response.status.isSuccess()) {
                    val failure = HttpChannelFailure(
                        code = response.status.value,
                        description = response.status.description,
                        url = url,
                    )
                    if (failure.code in retryPolicy.statusCodes && attempt < retryPolicy.delaysMs.size) {
                        throw RetryableChannelStatus()
                    }
                    throw httpFailure(failure)
                }
                block(
                    HttpChannelStream(
                        channel = response.bodyAsChannel(),
                        contentLength = knownLength?.takeIf { it > 0L }
                            ?: response.contentLength()
                            ?: -1L,
                        statusCode = response.status.value,
                        headers = response.headers,
                    ),
                )
            }
        } catch (_: RetryableChannelStatus) {
            delay(retryPolicy.delaysMs[attempt])
            attempt++
        }
    }
}

private class RetryableChannelStatus : RuntimeException(null, null, false, false)
