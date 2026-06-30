package com.riffle.core.network

import com.riffle.core.domain.DispatcherProvider
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager

/** [locatorJson] is the raw Readium `locator` object; [timestampMillis] its server `lastUpdate`. */
data class StorytellerPosition(val locatorJson: String, val timestampMillis: Long)

/**
 * Storyteller's single-peer reading-position endpoint (`/api/v2/books/{id}/positions`). The
 * position is a native Readium `Locator` plus a millisecond timestamp — no CFI translation needed
 * (contrast the ABS path, ADR 0013). Drives the Storyteller-only last-update-wins sync (ADR 0023).
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
    private val client: OkHttpClient,
    private val dispatchers: DispatcherProvider,
) : StorytellerPositionApi {

    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun getPosition(
        baseUrl: String,
        bookId: String,
        token: String,
        insecureAllowed: Boolean,
    ): NetworkResult<StorytellerPosition?> = withContext(dispatchers.io) {
        val http = if (insecureAllowed) client.trustAllCerts() else client
        val request = Request.Builder()
            .url("$baseUrl/api/v2/books/$bookId/positions")
            .addHeader("Authorization", "Bearer $token")
            .get()
            .build()
        try {
            http.newCall(request).execute().use { response ->
                when {
                    response.code == 404 -> NetworkResult.Success(null)
                    !response.isSuccessful -> NetworkResult.Offline(IOException("HTTP ${response.code}"))
                    else -> {
                        val raw = response.body?.string()
                            ?: return@use NetworkResult.Offline(IOException("Empty body"))
                        val root = json.parseToJsonElement(raw).jsonObject
                        val locator = root["locator"]?.jsonObject
                            ?: return@use NetworkResult.Success(null)
                        val ts = root["timestamp"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0L
                        NetworkResult.Success(StorytellerPosition(locator.toString(), ts))
                    }
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
    ): NetworkResult<Unit> = withContext(dispatchers.io) {
        val http = if (insecureAllowed) client.trustAllCerts() else client
        val payload = buildJsonObject {
            put("locator", json.parseToJsonElement(locatorJson))
            put("timestamp", JsonPrimitive(timestampMillis))
        }.toString()
        val request = Request.Builder()
            .url("$baseUrl/api/v2/books/$bookId/positions")
            .addHeader("Authorization", "Bearer $token")
            .patch(payload.toRequestBody("application/json".toMediaType()))
            .build()
        try {
            http.newCall(request).execute().use { response ->
                if (response.isSuccessful) NetworkResult.Success(Unit)
                else NetworkResult.Offline(IOException("HTTP ${response.code}"))
            }
        } catch (e: IOException) {
            NetworkResult.Offline(e)
        }
    }

    private fun OkHttpClient.trustAllCerts(): OkHttpClient {
        val trustAll = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>, authType: String) = Unit
            override fun checkServerTrusted(chain: Array<out X509Certificate>, authType: String) = Unit
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        }
        val sslContext = SSLContext.getInstance("TLS").apply { init(null, arrayOf(trustAll), SecureRandom()) }
        return newBuilder().sslSocketFactory(sslContext.socketFactory, trustAll).build()
    }
}
