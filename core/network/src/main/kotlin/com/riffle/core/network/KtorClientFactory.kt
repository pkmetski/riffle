package com.riffle.core.network

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient

val RIFFLE_JSON = Json { ignoreUnknownKeys = true; coerceInputValues = true }

/**
 * Creates a Ktor [HttpClient] backed by the OkHttp engine. The supplied [okHttpClient] carries the
 * disk cache, network interceptors, and TLS configuration; Ktor's content-negotiation plugin is
 * pre-installed with Riffle's [RIFFLE_JSON] instance so callers can use `response.body<T>()`.
 */
fun createDefaultHttpClient(okHttpClient: OkHttpClient): HttpClient = HttpClient(OkHttp) {
    engine {
        preconfigured = okHttpClient
    }
    install(ContentNegotiation) {
        json(RIFFLE_JSON)
    }
}

/**
 * Creates a Ktor [HttpClient] for raw byte streaming without ContentNegotiation. Streaming
 * endpoints set their own Accept headers and read raw bytes — ContentNegotiation would
 * silently append `application/json` to every Accept header, breaking content negotiation.
 */
fun createStreamingHttpClient(okHttpClient: OkHttpClient): HttpClient = HttpClient(OkHttp) {
    engine {
        preconfigured = okHttpClient
    }
}
