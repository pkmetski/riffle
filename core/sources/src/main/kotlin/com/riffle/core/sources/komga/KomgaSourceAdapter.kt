package com.riffle.core.sources.komga

import com.riffle.core.domain.AuthenticateResult
import com.riffle.core.domain.PendingSource
import com.riffle.core.models.InsecureConnectionType
import com.riffle.core.models.Library
import com.riffle.core.models.ServerType
import com.riffle.core.models.SourceType
import com.riffle.core.models.SourceUrl
import com.riffle.core.sources.SourceAdapter
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.isSuccess
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.serializer
import java.io.IOException
import java.util.Base64
import javax.net.ssl.SSLHandshakeException

/**
 * [SourceAdapter] for [SourceType.KOMGA]. Verifies (username, password) by GETting
 * `/api/v2/users/me` with HTTP Basic auth, then fetches `/api/v1/libraries` to seed the
 * library-picker screen. Falls back to `/api/v1/users/me` on 404 for older Komga builds.
 *
 * Takes a Ktor [HttpClient] so tests can inject a [io.ktor.client.engine.mock.MockEngine]-backed
 * client; production wires the shared OkHttp-backed client via Hilt in `core:data`.
 */
class KomgaSourceAdapter(
    private val httpClient: HttpClient,
) : SourceAdapter {
    override val sourceType: SourceType = SourceType.KOMGA

    override suspend fun authenticate(
        url: SourceUrl,
        username: String,
        password: String,
        insecureAllowed: Boolean,
        serverType: ServerType,
    ): AuthenticateResult {
        if (!insecureAllowed && url.value.startsWith("http://", ignoreCase = true)) {
            return AuthenticateResult.InsecureConnection(InsecureConnectionType.HTTP)
        }
        val base = url.value.trimEnd('/')
        val authHeader = buildBasicAuthHeader(username, password)

        val meResult = try {
            probeUserId(base, authHeader)
        } catch (e: SSLHandshakeException) {
            return AuthenticateResult.InsecureConnection(InsecureConnectionType.SELF_SIGNED)
        } catch (e: IOException) {
            return AuthenticateResult.NetworkError(e)
        }

        when {
            meResult.status == 401 || meResult.status == 403 -> return AuthenticateResult.WrongCredentials()
            meResult.status !in 200..399 -> return AuthenticateResult.NetworkError(
                IOException("Komga returned HTTP ${meResult.status} at /users/me")
            )
        }

        val libs = try {
            val response = httpClient.get("$base/api/v1/libraries") {
                header(HttpHeaders.Authorization, authHeader)
            }
            if (!response.status.isSuccess()) {
                return AuthenticateResult.LibraryFetchFailed(IOException("HTTP ${response.status.value}"))
            }
            KOMGA_JSON.decodeFromString(ListSerializer(serializer<KomgaLibraryDto>()), response.bodyAsText())
        } catch (e: IOException) {
            return AuthenticateResult.LibraryFetchFailed(e)
        }

        return AuthenticateResult.Success(
            PendingSource(
                url = url,
                username = username,
                userId = meResult.userId.orEmpty(),
                token = authHeader,
                password = password,
                insecureConnectionAllowed = insecureAllowed,
                libraries = libs.map {
                    Library(id = it.id, name = it.name, mediaType = "book", isUnsupported = false)
                },
                serverType = ServerType.AUDIOBOOKSHELF,
                sourceType = SourceType.KOMGA,
            )
        )
    }

    private suspend fun probeUserId(base: String, authHeader: String): MeProbeResult {
        val v2 = fetchMe("$base/api/v2/users/me", authHeader)
        return if (v2.status == 404) fetchMe("$base/api/v1/users/me", authHeader) else v2
    }

    private suspend fun fetchMe(url: String, authHeader: String): MeProbeResult {
        val response = httpClient.get(url) {
            header(HttpHeaders.Authorization, authHeader)
        }
        return if (response.status.isSuccess()) {
            val id = runCatching {
                KOMGA_JSON.decodeFromString(MeDto.serializer(), response.bodyAsText())
                    .id.takeIf { it.isNotBlank() }
            }.getOrNull()
            MeProbeResult(status = 200, userId = id)
        } else {
            MeProbeResult(status = response.status.value, userId = null)
        }
    }

    @Serializable
    private data class MeDto(@SerialName("id") val id: String = "")

    @Serializable
    private data class KomgaLibraryDto(
        val id: String,
        val name: String,
        @SerialName("unavailable") val unavailable: Boolean = false,
    )

    private data class MeProbeResult(val status: Int, val userId: String?)

    companion object {
        private val KOMGA_JSON = Json { ignoreUnknownKeys = true }

        fun buildBasicAuthHeader(username: String, password: String): String {
            val token = Base64.getEncoder().encodeToString("$username:$password".toByteArray(Charsets.UTF_8))
            return "Basic $token"
        }
    }
}
