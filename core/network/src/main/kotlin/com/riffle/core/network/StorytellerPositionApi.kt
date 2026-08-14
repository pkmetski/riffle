package com.riffle.core.network

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.patch
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.IOException

/** [locatorJson] is the raw Readium `locator` object; [timestampMillis] its server `lastUpdate`. */
data class StorytellerPosition(val locatorJson: String, val timestampMillis: Long)

/**
 * Storyteller's single-peer reading-position endpoint (`/api/v2/books/{id}/positions`). The
 * position is a native Readium `Locator` plus a millisecond timestamp — no CFI translation needed
 * (contrast the ABS path, ADR 0013). Drives the Storyteller-only last-update-wins sync (ADR 0027).
 *
 * `Success(null)` ⇒ no position is recorded yet (the old `NoPosition` variant).
 */
interface StorytellerPositionApi {
    suspend fun getPosition(baseUrl: String, bookId: String, token: String, insecureAllowed: Boolean): NetworkResult<StorytellerPosition?>
    suspend fun putPosition(
        baseUrl: String,
        bookId: String,
        locatorJson: String,
        timestampMillis: Long,
        token: String,
        insecureAllowed: Boolean,
    ): NetworkResult<Unit>
}

class StorytellerPositionApiImpl(
    private val client: HttpClient,
) : StorytellerPositionApi {

    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun getPosition(
        baseUrl: String,
        bookId: String,
        token: String,
        insecureAllowed: Boolean,
    ): NetworkResult<StorytellerPosition?> {
        return try {
            val response = http(insecureAllowed).get("$baseUrl/api/v2/books/$bookId/positions") {
                header(HttpHeaders.Authorization, "Bearer $token")
            }
            when {
                response.status == HttpStatusCode.NotFound -> NetworkResult.Success(null)
                !response.status.isSuccess() -> NetworkResult.Offline(IOException("HTTP ${response.status.value}"))
                else -> {
                    val raw = response.bodyAsText()
                    val root = json.parseToJsonElement(raw).jsonObject
                    val locator = root["locator"]?.jsonObject ?: return NetworkResult.Success(null)
                    val ts = root["timestamp"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0L
                    NetworkResult.Success(StorytellerPosition(locator.toString(), ts))
                }
            }
        } catch (e: IOException) {
            NetworkResult.Offline(e)
        }
    }

    override suspend fun putPosition(
        baseUrl: String,
        bookId: String,
        locatorJson: String,
        timestampMillis: Long,
        token: String,
        insecureAllowed: Boolean,
    ): NetworkResult<Unit> {
        return try {
            val payload = buildJsonObject {
                put("locator", json.parseToJsonElement(locatorJson))
                put("timestamp", JsonPrimitive(timestampMillis))
            }.toString()
            val response = http(insecureAllowed).patch("$baseUrl/api/v2/books/$bookId/positions") {
                header(HttpHeaders.Authorization, "Bearer $token")
                contentType(ContentType.Application.Json)
                setBody(payload)
            }
            if (response.status.isSuccess()) NetworkResult.Success(Unit)
            else NetworkResult.Offline(IOException("HTTP ${response.status.value}"))
        } catch (e: IOException) {
            NetworkResult.Offline(e)
        }
    }

    private fun http(insecureAllowed: Boolean): HttpClient =
        if (insecureAllowed) client.withInsecureTls() else client
}
