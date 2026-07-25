package com.riffle.core.catalog.gutenberg

import com.riffle.core.catalog.Catalog
import com.riffle.core.catalog.CatalogFactory
import com.riffle.core.models.Source
import com.riffle.core.models.SourceType
import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout

/**
 * Builds a [GutenbergCatalog] per Source. Because the Gutenberg Source is zero-config (Gutendex
 * origin hardcoded, anonymous read), the factory needs no per-source auth; every `create` call
 * yields an equivalent instance. Wired into `CatalogRegistry` via Hilt in `core:data`'s
 * `CatalogModule`.
 *
 * Derives a longer-timeout client so it tolerates slow Gutendex responses. Some `topic=…`
 * queries (Poetry most reproducibly) take well over the default 10s read timeout on
 * gutendex.com — 30s covers those without letting a truly-dead connection wedge indefinitely.
 *
 * Note: the previous OkHttp implementation forced HTTP/1.1 and a custom dispatcher to work
 * around Cloudflare H2 stalls. Those engine-level optimisations are deferred to Phase 3 when
 * the Ktor OkHttp engine is configured centrally per source type.
 */
class GutenbergCatalogFactory(
    sharedHttpClient: HttpClient,
    private val userAgent: String,
) : CatalogFactory {

    private val httpClient: HttpClient = sharedHttpClient.config {
        install(HttpTimeout) {
            connectTimeoutMillis = GUTENBERG_CONNECT_TIMEOUT_SEC * 1000
            requestTimeoutMillis = GUTENBERG_CALL_TIMEOUT_SEC * 1000
            socketTimeoutMillis = GUTENBERG_READ_TIMEOUT_SEC * 1000
        }
    }

    override val sourceType: SourceType = SourceType.GUTENBERG

    override suspend fun create(source: Source): Catalog {
        val http = GutenbergHttpClient(client = httpClient, userAgent = userAgent)
        return GutenbergCatalog(http = http, bytesClient = httpClient, userAgent = userAgent)
    }

    private companion object {
        const val GUTENBERG_CONNECT_TIMEOUT_SEC: Long = 15
        const val GUTENBERG_READ_TIMEOUT_SEC: Long = 30
        const val GUTENBERG_CALL_TIMEOUT_SEC: Long = 45
    }
}
