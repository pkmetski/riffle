package com.riffle.core.catalog.gutenberg

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

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

    suspend fun getString(url: String): String {
        var attempt = 0
        while (true) {
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", userAgent)
                .header("Accept", "application/json, text/html;q=0.5")
                .build()
            val response = client.newCall(request).await()
            // Body reading is blocking I/O — must NOT run on the resumed dispatcher, which is the
            // caller's context (Main for a Compose ViewModel). Enqueue-based await() resumes on
            // whatever thread the OkHttp Callback runs on but the coroutine machinery may hop back
            // to the caller's dispatcher; without the explicit withContext hop, r.body.string()
            // throws NetworkOnMainThreadException on strict-mode devices.
            val (status, body, msg) = withContext(Dispatchers.IO) {
                response.use { r ->
                    Triple(r.code, if (r.isSuccessful) r.body.string() else null, r.message)
                }
            }
            if ((status == 429 || status == 503) && attempt < retryDelaysMs.size) {
                delay(retryDelaysMs[attempt])
                attempt++
                continue
            }
            if (body == null) throw GutenbergHttpException(code = status, url = url, message = msg)
            return body
        }
        @Suppress("UNREACHABLE_CODE")
        error("unreachable")
    }

    suspend fun ping(url: String): Boolean {
        return try {
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", userAgent)
                .head()
                .build()
            client.newCall(request).await().use { it.isSuccessful }
        } catch (_: IOException) {
            false
        }
    }

    /**
     * Bridge OkHttp's [Call] to a coroutine so that cancelling the enclosing coroutine
     * (a fresh refresh cancelling the old one on rapid facet taps) cancels the underlying HTTP
     * request instead of leaving it stuck holding a dispatcher thread. Without this, rapid taps
     * pile up requests behind the connection pool and the fresh tap waits on stalled sockets —
     * observed as the "Couldn't reach Project Gutenberg" timeout even though the same URL
     * completes in ~100 ms via curl.
     */
    private suspend fun Call.await(): Response =
        suspendCancellableCoroutine { cont ->
            cont.invokeOnCancellation {
                try { cancel() } catch (_: Throwable) { /* best-effort */ }
            }
            enqueue(object : Callback {
                override fun onResponse(call: Call, response: Response) {
                    if (cont.isActive) cont.resume(response)
                    else response.close()
                }
                override fun onFailure(call: Call, e: IOException) {
                    if (cont.isActive) cont.resumeWithException(e)
                }
            })
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
