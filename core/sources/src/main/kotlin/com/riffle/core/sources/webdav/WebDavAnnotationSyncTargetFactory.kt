package com.riffle.core.sources.webdav

import com.riffle.core.domain.AnnotationSyncConfig
import com.riffle.core.domain.DispatcherProvider
import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout

/**
 * Builds a [WebDavAnnotationSyncTarget] from a saved [AnnotationSyncConfig], or returns null when
 * the config's base URL is malformed.
 *
 * Takes a Ktor [HttpClient] so tests can inject a [io.ktor.client.engine.mock.MockEngine]-backed
 * client. The factory applies WebDAV-specific timeouts on top of the shared base client.
 */
class WebDavAnnotationSyncTargetFactory(
    httpClient: HttpClient,
    private val dispatchers: DispatcherProvider,
) {
    private val httpClient = httpClient.config {
        install(HttpTimeout) {
            requestTimeoutMillis = WEBDAV_CALL_TIMEOUT_MS
            connectTimeoutMillis = WEBDAV_CONNECT_TIMEOUT_MS
            socketTimeoutMillis = WEBDAV_READ_TIMEOUT_MS
        }
    }

    fun create(config: AnnotationSyncConfig): WebDavAnnotationSyncTarget? {
        val url = parseWebDavBaseUrl(config.baseUrl) ?: return null
        return WebDavAnnotationSyncTarget(
            baseUrl = url,
            username = config.username,
            password = config.password,
            client = this.httpClient,
            dispatchers = dispatchers,
        )
    }

    companion object {
        private const val WEBDAV_CALL_TIMEOUT_MS = 30_000L
        private const val WEBDAV_CONNECT_TIMEOUT_MS = 10_000L
        private const val WEBDAV_READ_TIMEOUT_MS = 20_000L
    }
}
