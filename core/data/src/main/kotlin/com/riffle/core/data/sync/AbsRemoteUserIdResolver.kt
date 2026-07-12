package com.riffle.core.data.sync

import com.riffle.core.domain.RemoteUserIdResolver
import com.riffle.core.domain.Source
import com.riffle.core.network.AbsServerInfoApi
import javax.inject.Inject
import javax.inject.Singleton

/**
 * [RemoteUserIdResolver] for [com.riffle.core.domain.SourceType.ABS]. Delegates to
 * [AbsServerInfoApi.getCurrentUserId] (`GET /api/me` → `user.id`). Storyteller sources are
 * gated at the descriptor level (returns [com.riffle.core.domain.SyncNamespace.LocalOnly]) so
 * they never reach this resolver.
 */
@Singleton
class AbsRemoteUserIdResolver @Inject constructor(
    private val serverInfoApi: AbsServerInfoApi,
) : RemoteUserIdResolver {
    override suspend fun resolve(source: Source, token: String): String? =
        serverInfoApi.getCurrentUserId(
            baseUrl = source.url.value,
            token = token,
            insecureAllowed = source.insecureConnectionAllowed,
        )
}
