package com.riffle.core.network

import io.ktor.client.HttpClient
import io.ktor.client.engine.darwin.Darwin
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json

// The Darwin (NSURLSession) engine defaults to a 60s request timeout and a 7-day resource
// timeout, so a request to an unreachable host — e.g. an unbounded catalogue's real API when
// offline or on a throttled CI runner — hangs long enough to freeze teardown and blow test
// budgets. Android's OkHttp defaults to ~10s per phase; mirror that so iOS fails fast too.
private const val CONNECT_TIMEOUT_MS = 15_000L
private const val REQUEST_TIMEOUT_MS = 30_000L

actual fun createDefaultHttpClient(): HttpClient = HttpClient(Darwin) {
    install(ContentNegotiation) { json(RIFFLE_JSON) }
    install(HttpTimeout) {
        connectTimeoutMillis = CONNECT_TIMEOUT_MS
        requestTimeoutMillis = REQUEST_TIMEOUT_MS
    }
}

// Streaming downloads (EPUB/PDF/CBZ/audio) can legitimately run long, so only bound connection
// establishment here — a whole-request timeout would truncate large transfers.
actual fun createStreamingHttpClient(): HttpClient = HttpClient(Darwin) {
    install(HttpTimeout) {
        connectTimeoutMillis = CONNECT_TIMEOUT_MS
    }
}
