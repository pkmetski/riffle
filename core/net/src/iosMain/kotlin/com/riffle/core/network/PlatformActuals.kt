package com.riffle.core.network

import io.ktor.client.HttpClient
import io.ktor.client.engine.darwin.Darwin
import io.ktor.client.engine.darwin.DarwinHttpRequestException
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.*
import platform.Security.*

// NSURLErrorDomain codes that represent SSL/TLS failures (mirrors Android's SSLHandshakeException).
private val SSL_ERROR_CODES = -1200L downTo -1206L

internal actual fun isSSLHandshakeException(e: Throwable): Boolean {
    val nsError = (e as? DarwinHttpRequestException)?.origin ?: return false
    return nsError.domain == NSURLErrorDomain && nsError.code in SSL_ERROR_CODES
}

internal actual fun isIOException(e: Throwable): Boolean = e is NetworkOfflineException

internal actual fun newSSLHandshakeException(msg: String): Throwable = Exception(msg)

internal actual fun newIOException(msg: String): Throwable = Exception(msg)

// Mirrors the standard Darwin client (KtorClientFactory) but adds a ChallengeHandler that accepts
// self-signed certificates. Ktor's HttpClient.config {} types the engine block as the base
// HttpClientEngineConfig, which doesn't expose DarwinClientEngineConfig.handleChallenge, so we
// construct a fresh Darwin client rather than patching the existing one.
@OptIn(ExperimentalForeignApi::class)
actual fun HttpClient.withInsecureTls(): HttpClient = HttpClient(Darwin) {
    install(ContentNegotiation) { json(RIFFLE_JSON) }
    install(HttpTimeout) {
        connectTimeoutMillis = 15_000L
        requestTimeoutMillis = 30_000L
    }
    engine {
        handleChallenge { _, _, challenge, completionHandler ->
            if (challenge.protectionSpace.authenticationMethod == NSURLAuthenticationMethodServerTrust) {
                val serverTrust = challenge.protectionSpace.serverTrust
                @Suppress("ARGUMENT_TYPE_MISMATCH")
                completionHandler(
                    NSURLSessionAuthChallengeUseCredential,
                    serverTrust?.let { NSURLCredential.credentialForTrust(it) },
                )
            } else {
                @Suppress("ARGUMENT_TYPE_MISMATCH")
                completionHandler(NSURLSessionAuthChallengeUseCredential, null)
            }
        }
    }
}
