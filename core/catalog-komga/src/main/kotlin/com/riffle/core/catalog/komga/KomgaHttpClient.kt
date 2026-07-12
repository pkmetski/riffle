package com.riffle.core.catalog.komga

import kotlinx.coroutines.Dispatchers
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
 * Thin OkHttp wrapper for Komga's REST API. Stamps the `Authorization: Basic …` header on every
 * request; callers only supply the URL path.
 */
class KomgaHttpClient(
    private val client: OkHttpClient,
    private val basicAuthHeader: String,
    private val userAgent: String = "Riffle/dev (Android) komga-source",
) {

    /** GET [url], return response body string. Throws [KomgaHttpException] on non-2xx. */
    suspend fun getString(url: String): String {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", userAgent)
            .header("Authorization", basicAuthHeader)
            .header("Accept", "application/json")
            .build()
        val response = client.newCall(request).await()
        val (status, body, msg) = withContext(Dispatchers.IO) {
            response.use { r -> Triple(r.code, if (r.isSuccessful) r.body.string() else null, r.message) }
        }
        if (body == null) throw KomgaHttpException(code = status, url = url, message = msg)
        return body
    }

    /**
     * Execute a streaming GET on [url]. Caller receives the raw [Response] and MUST close it.
     * Throws [KomgaHttpException] on non-2xx (body is closed).
     */
    suspend fun getStreaming(url: String): Response {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", userAgent)
            .header("Authorization", basicAuthHeader)
            .build()
        val response = client.newCall(request).await()
        if (!response.isSuccessful) {
            val code = response.code
            val msg = response.message
            response.close()
            throw KomgaHttpException(code = code, url = url, message = msg)
        }
        return response
    }

    /** HEAD [url]; true if 2xx. Any exception → false. */
    suspend fun ping(url: String): Boolean = try {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", userAgent)
            .header("Authorization", basicAuthHeader)
            .head()
            .build()
        client.newCall(request).await().use { it.isSuccessful }
    } catch (_: IOException) {
        false
    }

    /** Status of a GET without reading the body. Returns HTTP code (or -1 on IOException). */
    suspend fun getStatus(url: String): Int = try {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", userAgent)
            .header("Authorization", basicAuthHeader)
            .build()
        client.newCall(request).await().use { it.code }
    } catch (_: IOException) {
        -1
    }

    private suspend fun Call.await(): Response = suspendCancellableCoroutine { cont ->
        cont.invokeOnCancellation {
            try { cancel() } catch (_: Throwable) { /* best-effort */ }
        }
        enqueue(object : Callback {
            override fun onResponse(call: Call, response: Response) {
                if (cont.isActive) cont.resume(response) else response.close()
            }
            override fun onFailure(call: Call, e: IOException) {
                if (cont.isActive) cont.resumeWithException(e)
            }
        })
    }
}

class KomgaHttpException(
    val code: Int,
    val url: String,
    message: String,
) : IOException("Komga HTTP $code for $url: $message")
