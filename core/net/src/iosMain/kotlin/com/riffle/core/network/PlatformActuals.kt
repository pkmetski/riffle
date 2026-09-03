package com.riffle.core.network

import io.ktor.client.HttpClient

internal actual fun isSSLHandshakeException(e: Throwable): Boolean = false

internal actual fun isIOException(e: Throwable): Boolean = false

internal actual fun newSSLHandshakeException(msg: String): Throwable = Exception(msg)

internal actual fun newIOException(msg: String): Throwable = Exception(msg)

actual fun HttpClient.withInsecureTls(): HttpClient = this
