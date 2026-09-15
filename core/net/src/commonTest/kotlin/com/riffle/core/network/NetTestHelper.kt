package com.riffle.core.network

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import io.ktor.utils.io.ByteReadChannel

const val BASE_URL = "http://test"

fun jsonHeaders() = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())

/** Creates a Ktor [MockEngine] that serves [responses] in queue order, paired with an [AbsApiClient]. */
fun mockAbsClient(vararg responses: Pair<String, HttpStatusCode>): Pair<MockEngine, AbsApiClient> {
    val (engine, client) = mockKtorClient(*responses)
    return engine to AbsApiClient(client)
}

/** Creates a Ktor [MockEngine] that serves [responses] in queue order, paired with a [KomgaServerInfoApiClient]. */
fun mockKomgaServerInfoClient(vararg responses: Pair<String, HttpStatusCode>): Pair<MockEngine, KomgaServerInfoApiClient> {
    val (engine, client) = mockKtorClient(*responses)
    return engine to KomgaServerInfoApiClient(client)
}

/** Creates a Ktor [MockEngine] that serves [responses] in queue order, paired with a [KomgaLibraryApiClient]. */
fun mockKomgaLibraryClient(vararg responses: Pair<String, HttpStatusCode>): Pair<MockEngine, KomgaLibraryApiClient> {
    val (engine, client) = mockKtorClient(*responses)
    return engine to KomgaLibraryApiClient(client)
}

/** Creates a Ktor [MockEngine] that serves [responses] in queue order, paired with a [StorytellerApiClient]. */
fun mockStorytellerClient(vararg responses: Pair<String, HttpStatusCode>): Pair<MockEngine, StorytellerApiClient> {
    val (engine, client) = mockKtorClient(*responses)
    return engine to StorytellerApiClient(client)
}

/** Creates an [HttpClient] backed by a queuing [MockEngine]. */
fun mockKtorClient(vararg responses: Pair<String, HttpStatusCode>): Pair<MockEngine, HttpClient> {
    val queue = ArrayDeque(responses.toList())
    val engine = MockEngine { _ ->
        val (body, status) = queue.removeFirst()
        respond(
            content = ByteReadChannel(body),
            status = status,
            headers = jsonHeaders(),
        )
    }
    val client = HttpClient(engine) {
        install(ContentNegotiation) { json(RIFFLE_JSON) }
    }
    return engine to client
}

/** Creates an [HttpClient] backed by a [MockEngine] that always throws a network error (simulates offline). */
fun offlineKtorClient(): HttpClient {
    val engine = MockEngine { throw NetworkOfflineException("Simulated network unreachable") }
    return HttpClient(engine) {
        install(ContentNegotiation) { json(RIFFLE_JSON) }
    }
}

/** Creates an [AbsApiClient] backed by an offline mock (always fails with a network error). */
fun offlineAbsClient(): AbsApiClient = AbsApiClient(offlineKtorClient())
