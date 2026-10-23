package com.riffle.core.data

import com.riffle.core.data.absbookmark.AbsBookmarkAnnotationSyncTargetFactory
import com.riffle.core.domain.AnnotationSyncConfig
import com.riffle.core.domain.AnnotationSyncConfigStore
import com.riffle.core.domain.CommitSourceResult
import com.riffle.core.domain.DefaultDispatcherProvider
import com.riffle.core.domain.PendingSource
import com.riffle.core.domain.SourceRepository
import com.riffle.core.domain.TokenStorage
import com.riffle.core.models.ServerType
import com.riffle.core.models.Source
import com.riffle.core.models.SourceType
import com.riffle.core.models.SourceUrl
import com.riffle.core.network.AbsBookmarkApi
import com.riffle.core.network.NetworkAbsBookmark
import com.riffle.core.network.NetworkResult
import com.riffle.core.sources.webdav.WebDavAnnotationSyncTargetFactory
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `commonTest` counterpart for the `awaitFirstLoad` / `observe()` tests in
 * `AnnotationSyncTargetHolderTest` (androidHostTest). The androidHostTest uses OkHttp; this suite
 * uses a Ktor MockEngine so the same state-machine assertions run on the iOS simulator in CI.
 *
 * These tests pin the startup-race fix: the holder seeds with an empty-sources state before the
 * init coroutine fires, and callers must not see a stale null when sources are already configured.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AnnotationSyncTargetHolderStartupTest {

    private val mockHttpClient = HttpClient(MockEngine { respond(ByteArray(0), HttpStatusCode.OK, headersOf()) })
    private val configStore = HolderStartupFakeConfigStore()
    private val webDavFactory = WebDavAnnotationSyncTargetFactory(mockHttpClient, DefaultDispatcherProvider)
    private val absBookmarkFactory = AbsBookmarkAnnotationSyncTargetFactory(
        absBookmarkApi = HolderStartupNoopAbsApi(),
        tokenStorage = HolderStartupNoopTokenStorage(),
    )
    private lateinit var scope: CoroutineScope

    private fun holder(
        sourceRepository: SourceRepository = HolderStartupEmptySourceRepository(),
    ) = AnnotationSyncTargetHolder(
        configStore = configStore,
        webDavFactory = webDavFactory,
        absBookmarkFactory = absBookmarkFactory,
        sourceRepository = sourceRepository,
        scope = scope,
    )

    @BeforeTest
    fun setUp() {
        scope = CoroutineScope(UnconfinedTestDispatcher())
    }

    @AfterTest
    fun tearDown() {
        scope.cancel()
    }

    @Test
    fun awaitFirstLoadResolvesAfterInitCoroutineFires() = runTest {
        val holder = holder()
        var completed = false
        val job = launch { holder.awaitFirstLoad(); completed = true }
        job.join()
        assertTrue(completed, "awaitFirstLoad must complete after the init coroutine's first emission")
    }

    @Test
    fun awaitFirstLoadIsIdempotent() = runTest {
        val holder = holder()
        holder.awaitFirstLoad()
        var completed = false
        val job = launch { holder.awaitFirstLoad(); completed = true }
        job.join()
        assertTrue(completed)
    }

    @Test
    fun observeEmitsNullInitiallyWhenNoConfigAndNoSources() = runTest {
        val holder = holder()
        assertNull(holder.observe().value, "no config and no sources => null target")
    }

    @Test
    fun observeEmitsNonNullAfterConfigIsSaved() = runTest {
        val holder = holder()
        configStore.emit(AnnotationSyncConfig("https://dav.example.org/anno", "u", "p"))
        assertNotNull(holder.observe().value, "config saved => non-null target")
    }

    @Test
    fun observeTransitionsNullToNonNullWhenAbsSourceAdded() = runTest {
        val mutableSources = HolderStartupMutableSourceRepository()
        val tokenStorage = HolderStartupInMemoryTokenStorage()
        val absFactory = AbsBookmarkAnnotationSyncTargetFactory(
            absBookmarkApi = HolderStartupNoopAbsApi(),
            tokenStorage = tokenStorage,
        )
        val holder = AnnotationSyncTargetHolder(
            configStore = configStore,
            webDavFactory = webDavFactory,
            absBookmarkFactory = absFactory,
            sourceRepository = mutableSources,
            scope = scope,
        )

        assertNull(holder.observe().value, "before any source, no target")

        val absSource = Source(
            id = "src-added",
            url = SourceUrl.parse("http://abs.local")!!,
            isActive = true,
            insecureConnectionAllowed = false,
            username = "user",
            type = SourceType.ABS,
            serverType = ServerType.AUDIOBOOKSHELF,
            absUserId = "abs-user-1",
        )
        tokenStorage.savedTokens[absSource.id] = "tok"
        mutableSources.emit(listOf(absSource))

        assertNotNull(holder.observe().value, "after abs source added with a token, target must be non-null")
    }

    @Test
    fun observeTransitionsNonNullToNullWhenSourceRemoved() = runTest {
        val mutableSources = HolderStartupMutableSourceRepository()
        val tokenStorage = HolderStartupInMemoryTokenStorage()
        val absSource = Source(
            id = "src-remove",
            url = SourceUrl.parse("http://abs.local")!!,
            isActive = true,
            insecureConnectionAllowed = false,
            username = "user",
            type = SourceType.ABS,
            serverType = ServerType.AUDIOBOOKSHELF,
            absUserId = "abs-user-2",
        )
        tokenStorage.savedTokens[absSource.id] = "tok"
        mutableSources.emit(listOf(absSource))

        val absFactory = AbsBookmarkAnnotationSyncTargetFactory(
            absBookmarkApi = HolderStartupNoopAbsApi(),
            tokenStorage = tokenStorage,
        )
        val holder = AnnotationSyncTargetHolder(
            configStore = configStore,
            webDavFactory = webDavFactory,
            absBookmarkFactory = absFactory,
            sourceRepository = mutableSources,
            scope = scope,
        )

        assertNotNull(holder.observe().value, "source present => non-null target")

        mutableSources.emit(emptyList())

        assertNull(holder.observe().value, "source removed, no WebDAV config => null target")
    }
}

private class HolderStartupFakeConfigStore : AnnotationSyncConfigStore {
    private val state = MutableStateFlow<AnnotationSyncConfig?>(null)
    override fun observe(): StateFlow<AnnotationSyncConfig?> = state
    override suspend fun save(config: AnnotationSyncConfig) { state.value = config }
    override suspend fun clear() { state.value = null }
    fun emit(value: AnnotationSyncConfig?) { state.value = value }
}

private class HolderStartupEmptySourceRepository : SourceRepository {
    private val state = MutableStateFlow<List<Source>>(emptyList())
    override fun observeAll(): Flow<List<Source>> = state
    override suspend fun getActive(): Source? = null
    override suspend fun commit(pending: PendingSource, hiddenLibraryIds: Set<String>): CommitSourceResult =
        CommitSourceResult.Failure(UnsupportedOperationException())
    override suspend fun setActive(sourceId: String) {}
    override suspend fun remove(sourceId: String) {}
    override suspend fun getSourceVersion(sourceId: String): String? = null
}

private class HolderStartupMutableSourceRepository : SourceRepository {
    private val state = MutableStateFlow<List<Source>>(emptyList())
    fun emit(sources: List<Source>) { state.value = sources }
    override fun observeAll(): Flow<List<Source>> = state
    override suspend fun getActive(): Source? = state.value.firstOrNull { it.isActive }
    override suspend fun commit(pending: PendingSource, hiddenLibraryIds: Set<String>): CommitSourceResult =
        CommitSourceResult.Failure(UnsupportedOperationException())
    override suspend fun setActive(sourceId: String) {}
    override suspend fun remove(sourceId: String) {}
    override suspend fun getSourceVersion(sourceId: String): String? = null
}

private class HolderStartupNoopTokenStorage : TokenStorage {
    override suspend fun saveToken(sourceId: String, token: String) {}
    override suspend fun getToken(sourceId: String): String? = null
    override suspend fun deleteToken(sourceId: String) {}
}

private class HolderStartupInMemoryTokenStorage : TokenStorage {
    val savedTokens: MutableMap<String, String> = mutableMapOf()
    override suspend fun saveToken(sourceId: String, token: String) { savedTokens[sourceId] = token }
    override suspend fun getToken(sourceId: String): String? = savedTokens[sourceId]
    override suspend fun deleteToken(sourceId: String) { savedTokens.remove(sourceId) }
}

private class HolderStartupNoopAbsApi : AbsBookmarkApi {
    override suspend fun createBookmark(
        baseUrl: String, itemId: String, timeSec: Int, title: String, token: String, insecureAllowed: Boolean,
    ): NetworkResult<NetworkAbsBookmark> = NetworkResult.Success(NetworkAbsBookmark(itemId, title, timeSec, 0L))
    override suspend fun updateBookmark(
        baseUrl: String, itemId: String, timeSec: Int, title: String, token: String, insecureAllowed: Boolean,
    ): NetworkResult<NetworkAbsBookmark> = NetworkResult.Success(NetworkAbsBookmark(itemId, title, timeSec, 0L))
    override suspend fun deleteBookmark(
        baseUrl: String, itemId: String, timeSec: Int, token: String, insecureAllowed: Boolean,
    ): NetworkResult<NetworkAbsBookmark> = NetworkResult.Success(NetworkAbsBookmark(itemId, "", timeSec, 0L))
    override suspend fun listBookmarks(
        baseUrl: String, token: String, insecureAllowed: Boolean,
    ): NetworkResult<List<NetworkAbsBookmark>> = NetworkResult.Success(emptyList())
}
