package com.riffle.core.catalog.abs

import com.riffle.core.catalog.Catalog
import com.riffle.core.catalog.CatalogFactory
import com.riffle.core.models.SourceType
import com.riffle.core.common.Clock
import com.riffle.core.domain.DeviceIdStore
import com.riffle.core.models.Source
import com.riffle.core.domain.TokenStorage
import com.riffle.core.network.AbsBookmarkApi
import com.riffle.core.network.AbsLibraryApi
import com.riffle.core.network.AbsPlaybackApi
import com.riffle.core.network.AbsServerInfoApi
import com.riffle.core.network.AbsSessionApi

/**
 * Builds an [AbsCatalog] per ABS Source row. The token comes from [TokenStorage]; if the row has
 * no stored token (fresh Source that hasn't completed login yet, credentials wiped) [create]
 * returns null so callers up the chain treat the Source as unreachable without crashing.
 */
class AbsCatalogFactory(
    private val libraryApi: AbsLibraryApi,
    private val playbackApi: AbsPlaybackApi,
    private val sessionApi: AbsSessionApi,
    private val bookmarkApi: AbsBookmarkApi,
    private val serverInfoApi: AbsServerInfoApi,
    private val tokenStorage: TokenStorage,
    private val deviceIdStore: DeviceIdStore,
    private val clock: Clock,
) : CatalogFactory {

    override val sourceType: SourceType = SourceType.ABS

    override suspend fun create(source: Source): Catalog? {
        val token = tokenStorage.getToken(source.id) ?: return null
        val deviceId = deviceIdStore.getOrCreate()
        val config = AbsCatalogConfig(
            baseUrl = source.url.value,
            token = token,
            insecureAllowed = source.insecureConnectionAllowed,
            deviceId = deviceId,
        )
        return AbsCatalog(
            config = config,
            libraryApi = libraryApi,
            playbackApi = playbackApi,
            sessionApi = sessionApi,
            bookmarkApi = bookmarkApi,
            serverInfoApi = serverInfoApi,
            clock = clock,
        )
    }
}

