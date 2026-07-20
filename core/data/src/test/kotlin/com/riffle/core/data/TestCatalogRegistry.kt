package com.riffle.core.data

import com.riffle.core.catalog.Catalog
import com.riffle.core.catalog.CatalogRegistry
import com.riffle.core.catalog.abs.AbsCatalog
import com.riffle.core.catalog.abs.AbsCatalogConfig
import com.riffle.core.common.Clock
import com.riffle.core.domain.DefaultDispatcherProvider
import com.riffle.core.models.Source
import com.riffle.core.domain.SourceRepository
import com.riffle.core.network.AbsApiClient
import com.riffle.core.network.AbsBookmarkApi
import com.riffle.core.network.AbsLibraryApi
import com.riffle.core.network.AbsPlaybackApi
import com.riffle.core.network.AbsServerInfoApi
import com.riffle.core.network.AbsSessionApi
import okhttp3.OkHttpClient

private val defaultTestClock = object : Clock {
    override fun nowMs() = System.currentTimeMillis()
    override fun nowNs() = System.nanoTime()
}

/**
 * Test helper that wraps a shared [AbsApiClient] behind [CatalogRegistry], resolving each Source to
 * a fresh [AbsCatalog] bound to that Source's URL + a per-Source token. Lets the ABS-shaped
 * repository tests keep their MockWebServer setup while consuming the same Catalog surface as
 * production.
 */
class TestCatalogRegistry(
    private val sourceRepository: SourceRepository,
    private val tokens: Map<String, String>,
    private val apiClient: AbsApiClient = AbsApiClient(OkHttpClient(), DefaultDispatcherProvider),
    private val clock: Clock = defaultTestClock,
    private val deviceId: String = "test-device",
) : CatalogRegistry {
    override suspend fun forActive(): Catalog? =
        sourceRepository.getActive()?.let { forSource(it) }

    override suspend fun forSourceId(sourceId: String): Catalog? =
        sourceRepository.getById(sourceId)?.let { forSource(it) }

    override suspend fun forSource(source: Source): Catalog? {
        val token = tokens[source.id] ?: return null
        val config = AbsCatalogConfig(
            baseUrl = source.url.value,
            token = token,
            insecureAllowed = source.insecureConnectionAllowed,
            deviceId = deviceId,
        )
        return AbsCatalog(
            config = config,
            libraryApi = apiClient,
            playbackApi = apiClient,
            sessionApi = apiClient,
            bookmarkApi = apiClient,
            serverInfoApi = apiClient,
            clock = clock,
        )
    }
}

/**
 * A registry that always returns the same catalog, for tests that construct a bespoke Catalog
 * (e.g. an [AbsCatalog] pointed at fake in-process APIs).
 */
class InlineCatalogRegistry(private val catalog: Catalog?) : CatalogRegistry {
    override suspend fun forActive(): Catalog? = catalog
    override suspend fun forSource(source: Source): Catalog? = catalog
    override suspend fun forSourceId(sourceId: String): Catalog? = catalog
}

/**
 * Assemble an [AbsCatalog] over caller-supplied API fakes. Any API left null becomes a no-op that
 * throws on call — pass what the test exercises.
 */
fun testAbsCatalog(
    baseUrl: String = "http://abs",
    token: String = "tok",
    insecureAllowed: Boolean = false,
    deviceId: String = "test-device",
    libraryApi: AbsLibraryApi,
    playbackApi: AbsPlaybackApi = NoopAbsPlaybackApi,
    sessionApi: AbsSessionApi = NoopAbsSessionApi,
    bookmarkApi: AbsBookmarkApi = NoopAbsBookmarkApi,
    serverInfoApi: AbsServerInfoApi = NoopAbsServerInfoApi,
    clock: Clock = defaultTestClock,
): Catalog = AbsCatalog(
    config = AbsCatalogConfig(baseUrl, token, insecureAllowed, deviceId),
    libraryApi = libraryApi,
    playbackApi = playbackApi,
    sessionApi = sessionApi,
    bookmarkApi = bookmarkApi,
    serverInfoApi = serverInfoApi,
    clock = clock,
)

/** A minimal [AbsLibraryApi] whose methods all return empty results — useful in tests that only
 *  exercise the sessionApi / playback path via [AbsCatalog]. */
object NoopLibraryApi : AbsLibraryApi {
    override suspend fun getLibraries(baseUrl: String, token: String, insecureAllowed: Boolean) =
        com.riffle.core.network.NetworkResult.Success(emptyList<com.riffle.core.network.NetworkLibrary>())
    override suspend fun getLibraryItems(baseUrl: String, libraryId: String, token: String, insecureAllowed: Boolean) =
        com.riffle.core.network.NetworkResult.Success(emptyList<com.riffle.core.network.NetworkLibraryItem>())
    override suspend fun getSeries(baseUrl: String, libraryId: String, token: String, insecureAllowed: Boolean) =
        com.riffle.core.network.NetworkResult.Success(emptyList<com.riffle.core.network.NetworkSeries>())
    override suspend fun getCollections(baseUrl: String, libraryId: String, token: String, insecureAllowed: Boolean) =
        com.riffle.core.network.NetworkResult.Success(emptyList<com.riffle.core.network.NetworkCollection>())
}

private object NoopAbsPlaybackApi : AbsPlaybackApi {
    override suspend fun openPlaybackSession(
        baseUrl: String, libraryItemId: String, deviceId: String, token: String, insecureAllowed: Boolean,
    ) = com.riffle.core.network.NetworkResult.Offline(RuntimeException("noop"))
}

internal object NoopAbsSessionApi : AbsSessionApi {
    override suspend fun syncEbookProgress(
        baseUrl: String, libraryItemId: String,
        payload: com.riffle.core.network.NetworkEbookProgressPayload, token: String, insecureAllowed: Boolean,
    ) = com.riffle.core.network.NetworkResult.Success(0L)

    override suspend fun syncAudiobookProgress(
        baseUrl: String, libraryItemId: String,
        payload: com.riffle.core.network.NetworkAudiobookProgressPayload, token: String, insecureAllowed: Boolean,
    ) = com.riffle.core.network.NetworkResult.Success(0L)

    override suspend fun getProgress(
        baseUrl: String, libraryItemId: String, token: String, insecureAllowed: Boolean,
    ) = com.riffle.core.network.NetworkResult.Offline(RuntimeException("noop"))
}

private object NoopAbsBookmarkApi : AbsBookmarkApi {
    override suspend fun createBookmark(
        baseUrl: String, itemId: String, timeSec: Int, title: String, token: String, insecureAllowed: Boolean,
    ) = com.riffle.core.network.NetworkResult.Offline(RuntimeException("noop"))
    override suspend fun updateBookmark(
        baseUrl: String, itemId: String, timeSec: Int, title: String, token: String, insecureAllowed: Boolean,
    ) = com.riffle.core.network.NetworkResult.Offline(RuntimeException("noop"))
    override suspend fun deleteBookmark(
        baseUrl: String, itemId: String, timeSec: Int, token: String, insecureAllowed: Boolean,
    ) = com.riffle.core.network.NetworkResult.Offline(RuntimeException("noop"))
    override suspend fun listBookmarks(baseUrl: String, token: String, insecureAllowed: Boolean) =
        com.riffle.core.network.NetworkResult.Success(emptyList<com.riffle.core.network.NetworkAbsBookmark>())
}

private object NoopAbsServerInfoApi : AbsServerInfoApi {
    override suspend fun getServerInfo(baseUrl: String, token: String, insecureAllowed: Boolean): String? = null
    override suspend fun getCurrentUserId(baseUrl: String, token: String, insecureAllowed: Boolean): String? = null
    override suspend fun getListeningStats(baseUrl: String, token: String, insecureAllowed: Boolean) =
        com.riffle.core.network.NetworkResult.Success(com.riffle.core.network.NetworkListeningStats(totalTimeSec = 0.0))
}
