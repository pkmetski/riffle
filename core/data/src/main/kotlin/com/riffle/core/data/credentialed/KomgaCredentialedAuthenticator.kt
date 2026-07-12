package com.riffle.core.data.credentialed

import com.riffle.core.catalog.komga.KomgaHttpException
import com.riffle.core.catalog.komga.KomgaHttpClient
import com.riffle.core.catalog.komga.buildBasicAuthHeader
import com.riffle.core.domain.AuthenticateResult
import com.riffle.core.domain.InsecureConnectionType
import com.riffle.core.domain.Library
import com.riffle.core.domain.PendingSource
import com.riffle.core.domain.ServerType
import com.riffle.core.domain.SourceType
import com.riffle.core.domain.SourceUrl
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.serializer
import okhttp3.OkHttpClient
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import javax.net.ssl.SSLHandshakeException

/**
 * [CredentialedAuthenticator] for [SourceType.KOMGA]. Verifies (username, password) by GETting
 * `/api/v2/users/me` with HTTP Basic auth, then fetches `/api/v1/libraries` to seed the
 * library-picker screen. Falls back to `/api/v1/users/me` on 404 for older Komga builds.
 */
@Singleton
class KomgaCredentialedAuthenticator @Inject constructor(
    private val okHttpClient: OkHttpClient,
) : CredentialedAuthenticator {
    override val sourceType: SourceType = SourceType.KOMGA

    override suspend fun authenticate(
        url: SourceUrl,
        username: String,
        password: String,
        insecureAllowed: Boolean,
        serverType: ServerType,
    ): AuthenticateResult {
        val header = buildBasicAuthHeader(username, password)
        val http = KomgaHttpClient(okHttpClient, header)
        if (!insecureAllowed && url.value.startsWith("http://", ignoreCase = true)) {
            return AuthenticateResult.InsecureConnection(InsecureConnectionType.HTTP)
        }
        val base = url.value.trimEnd('/')

        // Auth probe — /api/v2/users/me returns the current user or 401. We read the body when
        // the request succeeds so the response's `id` can be stashed into PendingSource.userId
        // (#529) as this source's cross-device annotation-sync identity; falling back to v1 for
        // pre-Komga-1.9 builds that only serve the older endpoint shape.
        val meResult = try {
            probeMe(http, base)
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

        // Libraries — one per browsable root.
        val libs = try {
            val body = http.getString("$base/api/v1/libraries")
            Json { ignoreUnknownKeys = true }
                .decodeFromString(ListSerializer(serializer<KomgaAuthLibraryDto>()), body)
        } catch (e: KomgaHttpException) {
            return AuthenticateResult.LibraryFetchFailed(e)
        } catch (e: IOException) {
            return AuthenticateResult.LibraryFetchFailed(e)
        }

        return AuthenticateResult.Success(
            PendingSource(
                url = url,
                username = username,
                userId = meResult.userId.orEmpty(),
                // Stash the FULL `Authorization` header value ("Basic <base64>") in the token slot
                // so cover-image fetch sites (which read `TokenStorage.getToken`) can pass it
                // straight through `String.asAuthHeader` without inventing a Komga-specific code
                // path. The value is opaque to the rest of the app; storing it here means it
                // survives a reinstall + a password rotation goes through the standard auth
                // pipeline that mints a fresh header. Password is also stored (as always) so
                // `KomgaCatalogFactory.create` can rebuild the header — the catalog owns its own
                // OkHttp requests and doesn't read this slot.
                token = header,
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

    private suspend fun probeMe(http: KomgaHttpClient, base: String): ProbeResult {
        val v2 = fetchMe(http, "$base/api/v2/users/me")
        if (v2.status == 404) return fetchMe(http, "$base/api/v1/users/me")
        return v2
    }

    private suspend fun fetchMe(http: KomgaHttpClient, url: String): ProbeResult {
        // Single GET so mock servers and real servers see exactly one request per probe. On
        // non-2xx the client throws [KomgaHttpException] carrying the status; on 2xx we parse
        // `id` for #529 (falling back to a userless success if the body doesn't match the
        // shape — auth still passes).
        return try {
            val body = http.getString(url)
            val id = runCatching {
                Json { ignoreUnknownKeys = true }
                    .decodeFromString(KomgaMeDto.serializer(), body).id.takeIf { it.isNotBlank() }
            }.getOrNull()
            ProbeResult(status = 200, userId = id)
        } catch (e: KomgaHttpException) {
            ProbeResult(status = e.code, userId = null)
        }
    }

    private data class ProbeResult(val status: Int, val userId: String?)

    @Serializable
    private data class KomgaMeDto(@SerialName("id") val id: String)

    @Serializable
    private data class KomgaAuthLibraryDto(
        val id: String,
        val name: String,
        @SerialName("unavailable") val unavailable: Boolean = false,
    )
}
