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
 * ADR 0063) and per-call routing parameters.
 *
 * Returns null when [config]'s base URL is malformed — callers treat null as "WebDAV unavailable."
 *
 * [webDavNamespace]: build the per-source namespace from a source-type slug and the WebDAV username.
 * The username is sanitized to `[a-zA-Z0-9_-]` so it's safe in filenames across all WebDAV servers.
 * Analogous to `komga_{userId}` in annotation sync — prevents collisions when two users share the
 * same WebDAV server root and both read the same Chitanka/Gutenberg book.
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
        finishedAt: suspend () -> Long?,
        clock: Clock,
    ): WebDavProgressRemote? {
        val baseUrl = parseWebDavBaseUrl(config.baseUrl) ?: return null
        val authHeader = "Basic " + Base64.getEncoder()
            .encodeToString("${config.username}:${config.password}".toByteArray())
        return WebDavProgressRemote(
            client = httpClient,
            authHeader = authHeader,
            progressFileUrl = WebDavProgressRemote.progressFileUrl(baseUrl.toString(), namespace, itemId),
            readingProgress = readingProgress,
            finishedAt = finishedAt,
            dispatchers = dispatchers,
            clock = clock,
        )
    }

    /** [ProgressRemote]<Double> for audiobook seconds, backed by the same WebDAV file. */
    fun createForAudio(
        config: AnnotationSyncConfig,
        namespace: String,
        itemId: String,
        readingProgress: suspend () -> Float,
        finishedAt: suspend () -> Long?,
        clock: Clock,
    ): ProgressRemote<Double>? =
        create(config, namespace, itemId, readingProgress, finishedAt, clock)?.asAudioRemote()

    companion object {
        private const val WEBDAV_CALL_TIMEOUT_MS = 30_000L
        private const val WEBDAV_CONNECT_TIMEOUT_MS = 10_000L
        private const val WEBDAV_READ_TIMEOUT_MS = 20_000L

        fun webDavNamespace(sourceTypeSlug: String, username: String): String {
            val safeUser = username.replace(Regex("[^a-zA-Z0-9_-]"), "_")
            return "${sourceTypeSlug}_$safeUser"
        }
    }
}
