package com.riffle.core.catalog.komga

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

private val JSON_MEDIA_TYPE = "application/json".toMediaType()

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
        return readSuccessOrThrow(method = "GET", url = url, response = response)
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

    /** POST [jsonBody] to [url]. Returns the response body as a string. Throws on non-2xx. */
    suspend fun postJson(url: String, jsonBody: String): String {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", userAgent)
            .header("Authorization", basicAuthHeader)
            .header("Accept", "application/json")
            .post(jsonBody.toRequestBody(JSON_MEDIA_TYPE))
            .build()
        val response = client.newCall(request).await()
        return readSuccessOrThrow(method = "POST", url = url, response = response)
    }

    /**
     * PATCH [jsonBody] to [url]. Discards the response body — Komga PATCH endpoints typically
     * return 204 No Content, so callers get success/failure via the exception, not a payload.
     */
    suspend fun patchJson(url: String, jsonBody: String) {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", userAgent)
            .header("Authorization", basicAuthHeader)
            .patch(jsonBody.toRequestBody(JSON_MEDIA_TYPE))
            .build()
        val response = client.newCall(request).await()
        readSuccessOrThrow(method = "PATCH", url = url, response = response)
    }

    /** DELETE [url]. Throws on non-2xx. */
    suspend fun delete(url: String) {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", userAgent)
            .header("Authorization", basicAuthHeader)
            .delete()
            .build()
        val response = client.newCall(request).await()
        readSuccessOrThrow(method = "DELETE", url = url, response = response)
    }

    /**
     * Consume [response]: return the body on 2xx (empty string for 204), or throw a
     * [KomgaHttpException] that carries the request method AND the response body. The body is
     * the difference between "403 Forbidden" (opaque) and something like "Cannot modify a
     * ReadList you don't own" — Komga's errors are only useful when we surface them. Truncate
     * to 500 chars so a large error page doesn't blow up log lines.
     */
    private suspend fun readSuccessOrThrow(method: String, url: String, response: Response): String {
        val (status, ok, bodyStr, statusMessage) = withContext(Dispatchers.IO) {
            response.use { r ->
                val bodyText = runCatching { r.body.string() }.getOrDefault("")
                Quad(r.code, r.isSuccessful, bodyText, r.message)
            }
        }
        if (!ok) {
            val truncated = if (bodyStr.length > 500) bodyStr.take(500) + "…(truncated)" else bodyStr
            throw KomgaHttpException(
                code = status,
                url = url,
                method = method,
                statusMessage = statusMessage,
                responseBody = truncated,
            )
        }
        return bodyStr
    }

    private data class Quad<A, B, C, D>(val a: A, val b: B, val c: C, val d: D)

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
    val method: String = "?",
    val statusMessage: String = "",
    val responseBody: String = "",
) : IOException(
    buildString {
        append("Komga HTTP ").append(code).append(' ').append(method).append(' ').append(url)
        if (statusMessage.isNotBlank()) append(" — ").append(statusMessage)
        if (responseBody.isNotBlank()) append(" | body: ").append(responseBody)
    },
) {
    // Backwards-compatible constructor used by legacy call sites (e.g. KomgaCatalog.openFile).
    // New sites should prefer the primary constructor so response bodies bubble into the log.
    constructor(code: Int, url: String, message: String) : this(
        code = code,
        url = url,
        method = "?",
        statusMessage = message,
        responseBody = "",
    )
}
