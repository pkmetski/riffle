package com.riffle.core.network

import okhttp3.OkHttpClient
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager

internal val TRUST_ALL_MANAGER = object : X509TrustManager {
    override fun checkClientTrusted(chain: Array<out X509Certificate>, authType: String) = Unit
    override fun checkServerTrusted(chain: Array<out X509Certificate>, authType: String) = Unit
    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
}

internal fun insecureSslContext(): SSLContext = SSLContext.getInstance("TLS").apply {
    init(null, arrayOf(TRUST_ALL_MANAGER), SecureRandom())
}

/**
 * Returns a copy of this OkHttpClient that accepts self-signed / untrusted TLS certificates.
 * Callers gate this on the per-server `insecureAllowed` flag.
 */
internal fun OkHttpClient.withInsecureTls(): OkHttpClient {
    val sslContext = insecureSslContext()
    return newBuilder()
        .sslSocketFactory(sslContext.socketFactory, TRUST_ALL_MANAGER)
        .build()
}
