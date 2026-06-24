package com.riffle.core.data

import com.riffle.core.domain.AnnotationSyncConfig
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import javax.inject.Inject

/**
 * Builds a [WebDavAnnotationSyncTarget] from a saved [AnnotationSyncConfig], or returns null when
 * the config's base URL is malformed. Shared by the DI graph (which observes the config store and
 * rebuilds when settings change) and the Settings "Test connection" action (which needs a
 * transient target before save).
 *
 * The factory derives a WebDAV-specific [OkHttpClient] from the app's shared one with explicit
 * call/read/write timeouts. The shared client uses the OkHttp defaults (no read/write/call
 * timeout), which would let a wedged Synology hang a PROPFIND/PUT indefinitely; per-call timeouts
 * keep `syncOnOpen` / `pushPending` reliably bounded.
 */
class WebDavAnnotationSyncTargetFactory @Inject constructor(
    sharedClient: OkHttpClient,
) {
    private val httpClient: OkHttpClient = sharedClient.newBuilder()
        .callTimeout(WEBDAV_CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .connectTimeout(WEBDAV_CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(WEBDAV_READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .writeTimeout(WEBDAV_WRITE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .build()

    fun create(config: AnnotationSyncConfig): WebDavAnnotationSyncTarget? {
        val url = parseWebDavBaseUrl(config.baseUrl) ?: return null
        return WebDavAnnotationSyncTarget(
            baseUrl = url,
            username = config.username,
            password = config.password,
            client = httpClient,
        )
    }

    companion object {
        // 30 s for the whole call (PROPFIND of a fully-listed share + parse can dwarf the others).
        private const val WEBDAV_CALL_TIMEOUT_SECONDS = 30L
        private const val WEBDAV_CONNECT_TIMEOUT_SECONDS = 10L
        private const val WEBDAV_READ_TIMEOUT_SECONDS = 20L
        private const val WEBDAV_WRITE_TIMEOUT_SECONDS = 20L
    }
}
