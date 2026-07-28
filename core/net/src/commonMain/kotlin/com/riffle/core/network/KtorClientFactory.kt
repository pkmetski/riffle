package com.riffle.core.network

import io.ktor.client.HttpClient

/** Creates a Ktor [HttpClient] configured with Riffle's [RIFFLE_JSON] content-negotiation. */
expect fun createDefaultHttpClient(): HttpClient

/** Creates a streaming Ktor [HttpClient] without ContentNegotiation. */
expect fun createStreamingHttpClient(): HttpClient
