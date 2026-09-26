package com.riffle.core.data.sources

import com.riffle.core.domain.TokenStorage
import com.riffle.core.models.ServerType
import com.riffle.core.models.Source
import com.riffle.core.models.SourceType
import com.riffle.core.network.AbsServerInfoApi
import com.riffle.core.network.KomgaServerInfoApi

/**
 * Resolves the server version string shown next to a source in Settings and the drawer. Shared
 * by both platforms' `SourceRepository.getSourceVersion` (#1101 — iOS used to hard-return null).
 *
 * - Storyteller exposes no server-info endpoint; the UI deliberately shows no version for it.
 * - Komga authenticates with Basic auth, so the stored password (not the token) is what it needs.
 * - Everything else is the ABS `/status` call, which returns null on any failure.
 */
class SourceVersionResolver(
    private val tokenStorage: TokenStorage,
    private val absServerInfoApi: AbsServerInfoApi,
    private val komgaServerInfoApi: KomgaServerInfoApi,
) {
    suspend fun resolve(source: Source): String? {
        if (source.serverType == ServerType.STORYTELLER_SERVICE) return null
        if (source.type == SourceType.KOMGA) {
            val password = tokenStorage.getPassword(source.id) ?: return null
            return komgaServerInfoApi.getServerVersion(
                baseUrl = source.url.value,
                username = source.username,
                password = password,
                insecureAllowed = source.insecureConnectionAllowed,
            )
        }
        val token = tokenStorage.getToken(source.id) ?: return null
        return absServerInfoApi.getServerInfo(
            baseUrl = source.url.value,
            token = token,
            insecureAllowed = source.insecureConnectionAllowed,
        )
    }
}
