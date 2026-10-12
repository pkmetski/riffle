package com.riffle.core.network

import io.ktor.client.HttpClient
import io.ktor.client.engine.darwin.DarwinHttpRequestException
import platform.Foundation.NSURLErrorDomain

// NSURLErrorDomain codes that represent SSL/TLS failures (mirrors Android's SSLHandshakeException).
private val SSL_ERROR_CODES = -1200L downTo -1206L

internal actual fun isSSLHandshakeException(e: Throwable): Boolean {
    val nsError = (e as? DarwinHttpRequestException)?.origin ?: return false
    return nsError.domain == NSURLErrorDomain && nsError.code in SSL_ERROR_CODES
}

internal actual fun isIOException(e: Throwable): Boolean = e is NetworkOfflineException

internal actual fun newSSLHandshakeException(msg: String): Throwable = Exception(msg)

internal actual fun newIOException(msg: String): Throwable = Exception(msg)

actual fun HttpClient.withInsecureTls(): HttpClient = this
