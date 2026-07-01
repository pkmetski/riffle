package com.riffle.core.network

import okhttp3.OkHttpClient
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager

/**
 * Returns a copy of this client that accepts self-signed / untrusted TLS certificates.
 *
 * Callers gate this on the per-server `insecureAllowed` flag (self-hosted Audiobookshelf and
 * Storyteller servers commonly run behind self-signed certs on homelab domains). Never call
 * unconditionally: the returned client bypasses certificate validation entirely.
 */
internal fun OkHttpClient.withInsecureTls(): OkHttpClient {
    val trustAll = object : X509TrustManager {
        override fun checkClientTrusted(chain: Array<out X509Certificate>, authType: String) = Unit
        override fun checkServerTrusted(chain: Array<out X509Certificate>, authType: String) = Unit
        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }
    val sslContext = SSLContext.getInstance("TLS").apply {
        init(null, arrayOf(trustAll), SecureRandom())
    }
    return newBuilder()
        .sslSocketFactory(sslContext.socketFactory, trustAll)
        .build()
}
