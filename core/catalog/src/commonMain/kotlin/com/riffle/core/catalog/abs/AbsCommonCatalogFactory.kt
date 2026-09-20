package com.riffle.core.catalog.abs

import com.riffle.core.catalog.Catalog
import com.riffle.core.catalog.CatalogFactory
import com.riffle.core.common.Clock
import com.riffle.core.domain.DeviceIdStore
import com.riffle.core.domain.TokenStorage
import com.riffle.core.models.Source
import com.riffle.core.models.SourceType
import com.riffle.core.network.AbsLibraryApi
import com.riffle.core.network.AbsServerInfoApi
import com.riffle.core.network.AbsSessionApi

/**
 * Builds an [AbsCommonCatalog] per ABS Source row on platforms that have no JVM-only ABS
 * file-transfer backend (iOS). JVM registers `AbsCatalogFactory` instead, which produces the
 * fuller `AbsCatalog`; both yield a Catalog that is a
 * [com.riffle.core.catalog.AudiobookProgressPeerCapability], which is what `CatalogRegistry`
 * consumers gate progress push on.
 *
 * The token comes from [TokenStorage]; if the row has no stored token (fresh Source that hasn't
 * completed login yet, credentials wiped) [create] returns null so callers up the chain treat the
 * Source as unreachable without crashing — identical contract to `AbsCatalogFactory`.
 */
class AbsCommonCatalogFactory(
    private val libraryApi: AbsLibraryApi,
    private val sessionApi: AbsSessionApi,
    private val serverInfoApi: AbsServerInfoApi,
    private val tokenStorage: TokenStorage,
    private val deviceIdStore: DeviceIdStore,
    private val clock: Clock,
) : CatalogFactory {

    override val sourceType: SourceType = SourceType.ABS

    override suspend fun create(source: Source): Catalog? {
        val token = tokenStorage.getToken(source.id) ?: return null
        return AbsCommonCatalog(
            config = AbsCatalogConfig(
                baseUrl = source.url.value,
                token = token,
                insecureAllowed = source.insecureConnectionAllowed,
                deviceId = deviceIdStore.getOrCreate(),
            ),
            libraryApi = libraryApi,
            sessionApi = sessionApi,
            serverInfoApi = serverInfoApi,
            clock = clock,
        )
    }
}
