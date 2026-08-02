package com.riffle.core.network

import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.contentLength
import io.ktor.http.isSuccess
import io.ktor.http.Headers
import io.ktor.utils.io.jvm.javaio.toInputStream
import kotlinx.coroutines.delay
import java.io.IOException
import java.io.InputStream

/** The live body of an HTTP response. Valid only for the duration of the stream callback. */
data class HttpByteStream(
    val inputStream: InputStream,
    val contentLength: Long,
    val statusCode: Int,
    val headers: Headers,
)

data class HttpRetryPolicy(
    val statusCodes: Set<Int>,
    val delaysMs: List<Long>,
) {
    companion object {
        val None = HttpRetryPolicy(emptySet(), emptyList())
    }
}

data class HttpStatusFailure(
    val code: Int,
    val description: String,
    val url: String,
)

class HttpStatusFailureException(failure: HttpStatusFailure) : IOException(
    "HTTP ${failure.code} for ${failure.url}: ${failure.description}",
)

/**
 * Runs [block] while a GET response is open, exposing bytes as they arrive. This is the single
 * implementation of response scoping, status handling, retry timing, and content-length selection
 * used by every network-backed Catalog. Callers configure only what varies: URL, headers, retry
 * policy, and the exception used for a terminal HTTP status.
 */
suspend fun <T> HttpClient.withHttpByteStream(
    url: String,
    headers: Map<String, String> = emptyMap(),
    knownLength: Long? = null,
    retryPolicy: HttpRetryPolicy = HttpRetryPolicy.None,
    httpFailure: (HttpStatusFailure) -> Throwable = ::HttpStatusFailureException,
    configureRequest: HttpRequestBuilder.() -> Unit = {},
    block: suspend (HttpByteStream) -> T,
): T {
    var attempt = 0
    while (true) {
        try {
            return prepareGet(url) {
                headers.forEach { (name, value) -> header(name, value) }
                configureRequest()
            }.execute { response ->
                if (!response.status.isSuccess()) {
                    val failure = HttpStatusFailure(
                        code = response.status.value,
                        description = response.status.description,
                        url = url,
                    )
                    if (failure.code in retryPolicy.statusCodes && attempt < retryPolicy.delaysMs.size) {
                        throw RetryableHttpStatus()
                    }
                    throw httpFailure(failure)
                }
                block(
                    HttpByteStream(
                        inputStream = response.bodyAsChannel().toInputStream(),
                        contentLength = knownLength?.takeIf { it > 0L }
                            ?: response.contentLength()
                            ?: -1L,
                        statusCode = response.status.value,
                        headers = response.headers,
                    ),
                )
            }
        } catch (_: RetryableHttpStatus) {
            delay(retryPolicy.delaysMs[attempt])
            attempt++
        }
    }
}

private class RetryableHttpStatus : RuntimeException(null, null, false, false)
