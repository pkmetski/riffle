package com.riffle.core.data.sync

import com.riffle.core.catalog.komga.KomgaHttpClient
import com.riffle.core.domain.RemoteUserIdResolver
import com.riffle.core.domain.Source
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import javax.inject.Inject
import javax.inject.Singleton

/**
 * [RemoteUserIdResolver] for [com.riffle.core.domain.SourceType.KOMGA]. `token` is the pre-built
 * `Authorization: Basic <base64>` header value stashed by [com.riffle.core.data.credentialed
 * .KomgaCredentialedAuthenticator] — pass it straight through the shared [KomgaHttpClient].
 *
 * Legacy Komga rows (installed before #529 wired the id into the add-source handshake) get
 * their id backfilled here on first sync.
 */
@Singleton
class KomgaRemoteUserIdResolver @Inject constructor(
    private val okHttpClient: OkHttpClient,
) : RemoteUserIdResolver {
    override suspend fun resolve(source: Source, token: String): String? {
        val base = source.url.value.trimEnd('/')
        val http = KomgaHttpClient(okHttpClient, token)
        val json = Json { ignoreUnknownKeys = true }
        // v2 is the current shape; v1 is the pre-Komga-1.9 fallback that still returns the same
        // `{ "id": "..." }` envelope.
        return try {
            val body = runCatching { http.getString("$base/api/v2/users/me") }
                .recoverCatching { http.getString("$base/api/v1/users/me") }
                .getOrElse { return null }
            json.decodeFromString(KomgaMeDto.serializer(), body).id.takeIf { it.isNotBlank() }
        } catch (_: Throwable) {
            null
        }
    }

    @Serializable
    private data class KomgaMeDto(@SerialName("id") val id: String)
}
