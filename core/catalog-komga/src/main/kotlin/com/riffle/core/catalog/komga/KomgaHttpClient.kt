package com.riffle.core.catalog.komga

import io.ktor.client.HttpClient
import io.ktor.client.request.accept
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.head
import io.ktor.client.request.header
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import java.io.IOException

/**
 * Thin Ktor wrapper for Komga's REST API. Stamps the `Authorization: Basic …` header on every
 * request; callers only supply the URL path.
 */
class KomgaHttpClient(
    private val client: HttpClient,
    private val basicAuthHeader: String,
    private val userAgent: String = "Riffle/dev (Android) komga-source",
) {

    /** GET [url], return response body string. Throws [KomgaHttpException] on non-2xx. */
    suspend fun getString(url: String): String {
        val response = client.get(url) {
            header(HttpHeaders.Authorization, basicAuthHeader)
            header(HttpHeaders.UserAgent, userAgent)
            accept(ContentType.Application.Json)
        }
        return readSuccessOrThrow("GET", url, response)
    }

    /** HEAD [url]; true if 2xx. Any exception → false. */
    suspend fun ping(url: String): Boolean = try {
        val response = client.head(url) {
            header(HttpHeaders.Authorization, basicAuthHeader)
            header(HttpHeaders.UserAgent, userAgent)
        }
        response.status.isSuccess()
    } catch (_: IOException) {
        false
    }

    /** POST [jsonBody] to [url]. Returns the response body as a string. Throws on non-2xx. */
    suspend fun postJson(url: String, jsonBody: String): String {
        val response = client.post(url) {
            header(HttpHeaders.Authorization, basicAuthHeader)
            header(HttpHeaders.UserAgent, userAgent)
            accept(ContentType.Application.Json)
            contentType(ContentType.Application.Json)
            setBody(jsonBody)
        }
        return readSuccessOrThrow("POST", url, response)
    }

    /**
     * PATCH [jsonBody] to [url]. Discards the response body — Komga PATCH endpoints typically
     * return 204 No Content (e.g. `/read-progress` — #528), so callers get success/failure via
     * the exception, not a payload.
     */
    suspend fun patchJson(url: String, jsonBody: String) {
        val response = client.patch(url) {
            header(HttpHeaders.Authorization, basicAuthHeader)
            header(HttpHeaders.UserAgent, userAgent)
            contentType(ContentType.Application.Json)
            setBody(jsonBody)
        }
        readSuccessOrThrow("PATCH", url, response)
    }

    /** DELETE [url]. Throws on non-2xx. */
    suspend fun delete(url: String) {
        val response = client.delete(url) {
            header(HttpHeaders.Authorization, basicAuthHeader)
            header(HttpHeaders.UserAgent, userAgent)
        }
        readSuccessOrThrow("DELETE", url, response)
    }

    /** Status of a GET without reading the body. Returns HTTP code (or -1 on IOException). */
    suspend fun getStatus(url: String): Int = try {
        client.get(url) {
            header(HttpHeaders.Authorization, basicAuthHeader)
            header(HttpHeaders.UserAgent, userAgent)
        }.status.value
    } catch (_: IOException) {
        -1
    }

    /**
     * Consume [response]: return the body on 2xx (empty string for 204), or throw a
     * [KomgaHttpException] that carries the request method AND the response body. The body is
     * the difference between "403 Forbidden" (opaque) and something like "Cannot modify a
     * ReadList you don't own" — Komga's errors are only useful when we surface them. Truncate
     * to 500 chars so a large error page doesn't blow up log lines.
     */
    private suspend fun readSuccessOrThrow(method: String, url: String, response: HttpResponse): String {
        val bodyStr = runCatching { response.bodyAsText() }.getOrDefault("")
        if (!response.status.isSuccess()) {
            val truncated = if (bodyStr.length > 500) bodyStr.take(500) + "…(truncated)" else bodyStr
            throw KomgaHttpException(
                code = response.status.value,
                url = url,
                method = method,
                statusMessage = response.status.description,
                responseBody = truncated,
            )
        }
        return bodyStr
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
