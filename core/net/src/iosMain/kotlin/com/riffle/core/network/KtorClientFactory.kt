package com.riffle.core.network

import io.ktor.client.HttpClient
import io.ktor.client.engine.darwin.Darwin
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json

actual fun createDefaultHttpClient(): HttpClient = HttpClient(Darwin) {
    install(ContentNegotiation) { json(RIFFLE_JSON) }
}

actual fun createStreamingHttpClient(): HttpClient = HttpClient(Darwin)
