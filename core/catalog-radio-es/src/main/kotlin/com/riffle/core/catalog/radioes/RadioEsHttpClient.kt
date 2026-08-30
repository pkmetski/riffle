package com.riffle.core.catalog.radioes

import io.ktor.client.HttpClient
import io.ktor.client.request.accept
import io.ktor.client.request.get
import io.ktor.client.request.head
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.isSuccess
import kotlinx.coroutines.delay
import java.io.IOException

class RadioEsHttpClient(
    private val client: HttpClient,
    private val userAgent: String,
    private val acceptLanguage: String = "en",
    private val retryDelaysMs: List<Long> = DEFAULT_RETRY_DELAYS_MS,
) {
    suspend fun getString(url: String, acceptLanguageOverride: String? = null): String {
        var attempt = 0
        while (true) {
            val response = client.get(url) {
                header(HttpHeaders.UserAgent, userAgent)
                header(HttpHeaders.AcceptLanguage, acceptLanguageOverride ?: acceptLanguage)
                accept(ContentType.Application.Json)
            }
            if ((response.status.value == 429 || response.status.value == 503) && attempt < retryDelaysMs.size) {
                delay(retryDelaysMs[attempt++])
                continue
            }
            if (!response.status.isSuccess()) {
                throw RadioEsHttpException(response.status.value, url, response.status.description)
            }
            return response.bodyAsText()
        }
    }

    suspend fun ping(url: String): Boolean = try {
        client.head(url) { header(HttpHeaders.UserAgent, userAgent) }.status.isSuccess()
    } catch (_: IOException) { false }

    companion object {
        val DEFAULT_RETRY_DELAYS_MS: List<Long> = listOf(1_500L, 3_000L)
    }
}

class RadioEsHttpException(
    val code: Int,
    val url: String,
    message: String,
) : IOException("RadioEs HTTP $code for $url: $message")
