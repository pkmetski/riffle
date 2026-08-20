package com.riffle.core.network

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.isSuccess
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

class KomgaServerInfoApiClient(
    private val httpClient: HttpClient,
) : KomgaServerInfoApi {

    override suspend fun getServerVersion(
        baseUrl: String,
        username: String,
        password: String,
        insecureAllowed: Boolean,
    ): String? = KtorClassifier.classify {
        val client = if (insecureAllowed) httpClient.withInsecureTls() else httpClient
        val response = client.get("${baseUrl.trimEnd('/')}/actuator/info") {
            header(HttpHeaders.Authorization, buildBasicAuthHeader(username, password))
        }
        if (!response.status.isSuccess()) return@classify null
        parseActuatorVersion(response.bodyAsText())
    }.getOrNull()

    companion object {
        @OptIn(ExperimentalEncodingApi::class)
        internal fun buildBasicAuthHeader(username: String, password: String): String {
            val encoded = Base64.encode("$username:$password".encodeToByteArray())
            return "Basic $encoded"
        }

        internal fun parseActuatorVersion(body: String): String? {
            val obj = runCatching {
                Json.parseToJsonElement(body).jsonObject
            }.getOrNull() ?: return null
            val build = (obj["build"] as? JsonObject) ?: return null
            return (build["version"] as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() }
        }
    }
}
