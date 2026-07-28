package com.riffle.core.network

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import okhttp3.OkHttpClient

/**
 * Creates a Ktor [HttpClient] backed by the OkHttp engine with a pre-configured [OkHttpClient].
 * The supplied client carries the disk cache, network interceptors, and TLS configuration.
 */
fun createDefaultHttpClient(okHttpClient: OkHttpClient): HttpClient = HttpClient(OkHttp) {
    engine { preconfigured = okHttpClient }
    install(ContentNegotiation) { json(RIFFLE_JSON) }
}

/**
 * Creates a Ktor [HttpClient] for raw byte streaming without ContentNegotiation. Streaming
 * endpoints set their own Accept headers and read raw bytes — ContentNegotiation would
 * silently append `application/json` to every Accept header, breaking content negotiation.
 */
fun createStreamingHttpClient(okHttpClient: OkHttpClient): HttpClient = HttpClient(OkHttp) {
    engine { preconfigured = okHttpClient }
}

/** Common factory called from commonMain expect. Uses a fresh default OkHttpClient. */
actual fun createDefaultHttpClient(): HttpClient = createDefaultHttpClient(OkHttpClient())

actual fun createStreamingHttpClient(): HttpClient = createStreamingHttpClient(OkHttpClient())
