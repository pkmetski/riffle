package com.riffle.core.sources.webdav

import com.riffle.core.common.Clock
import com.riffle.core.domain.AnnotationSyncConfig
import com.riffle.core.domain.DispatcherProvider
import com.riffle.core.domain.ProgressRemote
import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import java.util.Base64

/**
 * Builds a [WebDavProgressRemote] from an [AnnotationSyncConfig] (shared WebDAV credentials,
 * ADR 0062) and per-call routing parameters.
 *
 * Returns null when [config]'s base URL is malformed — callers treat null as "WebDAV unavailable."
 */
class WebDavProgressRemoteFactory(
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

    fun create(
        config: AnnotationSyncConfig,
        namespace: String,
        itemId: String,
        readingProgress: suspend () -> Float,
        clock: Clock,
    ): ProgressRemote<String>? {
        val baseUrl = parseWebDavBaseUrl(config.baseUrl) ?: return null
        val authHeader = "Basic " + Base64.getEncoder()
            .encodeToString("${config.username}:${config.password}".toByteArray())
        return WebDavProgressRemote(
            client = httpClient,
            authHeader = authHeader,
            progressFileUrl = WebDavProgressRemote.progressFileUrl(baseUrl.toString(), namespace, itemId),
            readingProgress = readingProgress,
            dispatchers = dispatchers,
            clock = clock,
        )
    }

    companion object {
        private const val WEBDAV_CALL_TIMEOUT_MS = 30_000L
        private const val WEBDAV_CONNECT_TIMEOUT_MS = 10_000L
        private const val WEBDAV_READ_TIMEOUT_MS = 20_000L
    }
}
