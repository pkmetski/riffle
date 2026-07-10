package com.riffle.core.catalog.chitanka

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

/**
 * Thin OkHttp wrapper for Chitanka/Gramofonche HTML fetches.
 *
 * Honors upstream 429 rate-limiting with the same schedule as the reference
 * TypeScript scraper: 3 attempts, backing off 1.5s then 3s. Identifies itself
 * with a stable [userAgent] so the origin can throttle or block cleanly if it
 * needs to. Every other 4xx/5xx propagates as [ChitankaHttpException] without
 * retry.
 *
 * Percent-encoding of Cyrillic paths is done at the [ChitankaScraper.toAbsolute]
 * layer (via `java.net.URI`); by the time a URL reaches this client it's already
 * ASCII-safe.
 */
class ChitankaHttpClient(
    private val client: OkHttpClient,
    private val userAgent: String,
    private val retryDelaysMs: List<Long> = DEFAULT_RETRY_DELAYS_MS,
) {

    /**
     * GET [url] and return the response body as a String. Retries on HTTP 429 up to
     * [retryDelaysMs].size + 1 total attempts.
     */
    suspend fun getString(url: String): String = withContext(Dispatchers.IO) {
        var attempt = 0
        while (true) {
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", userAgent)
                .header("Accept-Language", "bg,en;q=0.5")
                .build()
            val (status, body, msg) = client.newCall(request).execute().use { r ->
                Triple(r.code, if (r.isSuccessful) r.body.string() else null, r.message)
            }
            if (status == 429 && attempt < retryDelaysMs.size) {
                delay(retryDelaysMs[attempt])
                attempt++
                continue
            }
            if (body == null) throw ChitankaHttpException(code = status, url = url, message = msg)
            return@withContext body
        }
        @Suppress("UNREACHABLE_CODE")
        error("unreachable")
    }

    /** True when [url] responds 2xx to a HEAD request. Used by [connectivityCheck]. */
    suspend fun ping(url: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", userAgent)
                .head()
                .build()
            client.newCall(request).execute().use { it.isSuccessful }
        } catch (_: IOException) {
            false
        }
    }

    /**
     * Content-Length reported by the origin on a HEAD, or `null` on non-2xx / network error.
     * Used by the audiobook capability to estimate per-track duration (bytes / assumed bitrate)
     * since Gramofonche exposes no per-track duration metadata.
     */
    suspend fun headContentLength(url: String): Long? = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", userAgent)
                .head()
                .build()
            client.newCall(request).execute().use { r ->
                if (!r.isSuccessful) return@withContext null
                r.header("Content-Length")?.toLongOrNull()
            }
        } catch (_: IOException) {
            null
        }
    }

    companion object {
        val DEFAULT_RETRY_DELAYS_MS: List<Long> = listOf(1_500L, 3_000L)
    }
}

/** Non-retryable HTTP failure. Retryable 429s are handled internally. */
class ChitankaHttpException(
    val code: Int,
    val url: String,
    message: String,
) : IOException("Chitanka HTTP $code for $url: $message")
