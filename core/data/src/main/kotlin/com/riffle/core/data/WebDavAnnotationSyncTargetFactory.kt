package com.riffle.core.data

import com.riffle.core.domain.AnnotationSyncConfig
import com.riffle.core.domain.DispatcherProvider
import com.riffle.core.network.createDefaultHttpClient
import io.ktor.client.plugins.HttpTimeout
import okhttp3.OkHttpClient
import javax.inject.Inject

/**
 * Builds a [WebDavAnnotationSyncTarget] from a saved [AnnotationSyncConfig], or returns null when
 * the config's base URL is malformed. Shared by the DI graph (which observes the config store and
 * rebuilds when settings change) and the Settings "Test connection" action (which needs a
 * transient target before save).
 *
 * Derives a WebDAV-specific Ktor [io.ktor.client.HttpClient] from the app's shared OkHttp client
 * with explicit call/read/write timeouts. The shared client has no timeout defaults, which would
 * let a wedged Synology hang a PROPFIND/PUT indefinitely; per-call timeouts keep
 * `syncOnOpen` / `pushPending` reliably bounded.
 */
class WebDavAnnotationSyncTargetFactory @Inject constructor(
    sharedOkHttpClient: OkHttpClient,
    private val dispatchers: DispatcherProvider,
) {
    private val httpClient = createDefaultHttpClient(sharedOkHttpClient).config {
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
            client = httpClient,
            dispatchers = dispatchers,
        )
    }

    companion object {
        // 30 s for the whole call (PROPFIND of a fully-listed share + parse can dwarf the others).
        private const val WEBDAV_CALL_TIMEOUT_MS = 30_000L
        private const val WEBDAV_CONNECT_TIMEOUT_MS = 10_000L
        private const val WEBDAV_READ_TIMEOUT_MS = 20_000L
    }
}
