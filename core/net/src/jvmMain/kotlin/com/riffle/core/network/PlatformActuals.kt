package com.riffle.core.network

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttpConfig
import java.io.IOException
import javax.net.ssl.SSLHandshakeException

internal actual fun isSSLHandshakeException(e: Throwable): Boolean = e is SSLHandshakeException
internal actual fun isIOException(e: Throwable): Boolean = e is IOException
internal actual fun newSSLHandshakeException(msg: String): Throwable = SSLHandshakeException(msg)
internal actual fun newIOException(msg: String): Throwable = IOException(msg)

actual fun HttpClient.withInsecureTls(): HttpClient {
    val sslContext = insecureSslContext()
    return config {
        engine {
            (this as OkHttpConfig).config {
                sslSocketFactory(sslContext.socketFactory, TRUST_ALL_MANAGER)
                hostnameVerifier { _, _ -> true }
            }
        }
    }
}
