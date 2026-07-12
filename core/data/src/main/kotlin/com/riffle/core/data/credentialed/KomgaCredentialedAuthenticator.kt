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

        // Auth probe — /api/v2/users/me returns the current user or 401.
        val meStatus = try {
            probeMe(http, base)
        } catch (e: SSLHandshakeException) {
            return AuthenticateResult.InsecureConnection(InsecureConnectionType.SELF_SIGNED)
        } catch (e: IOException) {
            return AuthenticateResult.NetworkError(e)
        }
        when {
            meStatus == 401 || meStatus == 403 -> return AuthenticateResult.WrongCredentials()
            meStatus !in 200..399 -> return AuthenticateResult.NetworkError(
                IOException("Komga returned HTTP $meStatus at /users/me")
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
                userId = "",
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

    private suspend fun probeMe(http: KomgaHttpClient, base: String): Int {
        val v2 = http.getStatus("$base/api/v2/users/me")
        if (v2 == 404) return http.getStatus("$base/api/v1/users/me")
        return v2
    }

    @Serializable
    private data class KomgaAuthLibraryDto(
        val id: String,
        val name: String,
        @SerialName("unavailable") val unavailable: Boolean = false,
    )
}
