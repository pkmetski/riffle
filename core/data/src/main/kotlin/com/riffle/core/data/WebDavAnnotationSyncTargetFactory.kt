package com.riffle.core.data

import com.riffle.core.domain.AnnotationSyncConfig
import okhttp3.OkHttpClient
import javax.inject.Inject

/**
 * Builds a [WebDavAnnotationSyncTarget] from a saved [AnnotationSyncConfig], or
 * returns null when the config's base URL is malformed. Shared by the DI graph
 * (which observes the config store and rebuilds when settings change) and the
 * Settings "Test connection" action (which needs a transient target before save).
 */
class WebDavAnnotationSyncTargetFactory @Inject constructor(
    private val httpClient: OkHttpClient,
) {
    fun create(config: AnnotationSyncConfig): WebDavAnnotationSyncTarget? {
        val url = parseWebDavBaseUrl(config.baseUrl) ?: return null
        return WebDavAnnotationSyncTarget(
            baseUrl = url,
            username = config.username,
            password = config.password,
            client = httpClient,
        )
    }
}
