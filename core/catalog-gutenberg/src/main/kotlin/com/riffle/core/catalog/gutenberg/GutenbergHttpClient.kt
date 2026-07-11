package com.riffle.core.catalog.gutenberg

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

/**
 * Thin OkHttp wrapper for Gutendex + gutenberg.org fetches. Mirrors the shape of the Chitanka
 * client so both public-catalogue sources have the same throttling behaviour.
 *
 * Gutendex is well-behaved and generally returns quickly; the retry schedule below matters more
 * for gutenberg.org's mirrored EPUB downloads, which occasionally 503 during mirror rotation.
 */
class GutenbergHttpClient(
    private val client: OkHttpClient,
    private val userAgent: String,
    private val retryDelaysMs: List<Long> = DEFAULT_RETRY_DELAYS_MS,
) {

    suspend fun getString(url: String): String = withContext(Dispatchers.IO) {
        var attempt = 0
        while (true) {
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", userAgent)
                .header("Accept", "application/json, text/html;q=0.5")
                .build()
            val (status, body, msg) = client.newCall(request).execute().use { r ->
                Triple(r.code, if (r.isSuccessful) r.body.string() else null, r.message)
            }
            if ((status == 429 || status == 503) && attempt < retryDelaysMs.size) {
                delay(retryDelaysMs[attempt])
                attempt++
                continue
            }
            if (body == null) throw GutenbergHttpException(code = status, url = url, message = msg)
            return@withContext body
        }
        @Suppress("UNREACHABLE_CODE")
        error("unreachable")
    }

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

    companion object {
        val DEFAULT_RETRY_DELAYS_MS: List<Long> = listOf(1_500L, 3_000L)
    }
}

class GutenbergHttpException(
    val code: Int,
    val url: String,
    message: String,
) : IOException("Gutenberg HTTP $code for $url: $message")
