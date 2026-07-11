package com.riffle.core.catalog.gutenberg

import com.riffle.core.catalog.Catalog
import com.riffle.core.catalog.CatalogFactory
import com.riffle.core.domain.Source
import com.riffle.core.domain.SourceType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import java.util.concurrent.TimeUnit

/**
 * Builds a [GutenbergCatalog] per Source. Because the Gutenberg Source is zero-config (Gutendex
 * origin hardcoded, anonymous read), the factory needs no per-source auth; every `create` call
 * yields an equivalent instance. Wired into `CatalogRegistry` via Hilt in `core:data`'s
 * `CatalogModule`.
 *
 * Derives a longer-timeout client via [OkHttpClient.newBuilder] so it shares the connection
 * pool + dispatchers with the app-wide singleton but tolerates slow Gutendex responses. Some
 * `topic=…` queries (Poetry most reproducibly) take well over the default 10s read timeout on
 * gutendex.com — verified via `adb logcat -d | grep RIFFLE_GB` showing
 * `SocketTimeoutException: timeout` while curl to the same URL from the host completes.
 * 30s covers those without letting a truly-dead connection wedge the browse indefinitely.
 */
class GutenbergCatalogFactory(
    okHttpClient: OkHttpClient,
    private val userAgent: String,
) : CatalogFactory {

    // HTTP/1.1-only: Gutendex sits behind Cloudflare. Empirically OkHttp's default HTTP/2
    // path stalls on some `topic=…` queries — the connection stays open past the read timeout
    // even though curl to the same URL returns in <200 ms. Forcing HTTP/1.1 sidesteps the H2
    // stream-multiplexing edge case entirely; the tradeoff (a fresh TCP per unpipelined
    // request) is trivial for this Source's request volume.
    private val httpClient: OkHttpClient = okHttpClient.newBuilder()
        .protocols(listOf(Protocol.HTTP_1_1))
        .connectTimeout(GUTENBERG_CONNECT_TIMEOUT_SEC, TimeUnit.SECONDS)
        .readTimeout(GUTENBERG_READ_TIMEOUT_SEC, TimeUnit.SECONDS)
        .callTimeout(GUTENBERG_CALL_TIMEOUT_SEC, TimeUnit.SECONDS)
        .build()

    override val sourceType: SourceType = SourceType.GUTENBERG

    override suspend fun create(source: Source): Catalog {
        val http = GutenbergHttpClient(client = httpClient, userAgent = userAgent)
        return GutenbergCatalog(http = http, bytesClient = httpClient, userAgent = userAgent)
    }

    private companion object {
        const val GUTENBERG_CONNECT_TIMEOUT_SEC: Long = 15
        const val GUTENBERG_READ_TIMEOUT_SEC: Long = 30
        // Upper bound across DNS + connect + TLS + request + response. Prevents a genuinely
        // dead connection from hanging the browse forever even if individual timeouts don't
        // trip (e.g. slow drip on the socket that keeps read-timeout resetting).
        const val GUTENBERG_CALL_TIMEOUT_SEC: Long = 45
    }
}
