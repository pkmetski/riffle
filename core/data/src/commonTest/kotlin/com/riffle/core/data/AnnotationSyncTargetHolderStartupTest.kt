package com.riffle.core.data

import com.riffle.core.data.absbookmark.AbsBookmarkAnnotationSyncTargetFactory
import com.riffle.core.domain.AnnotationSyncConfig
import com.riffle.core.domain.AnnotationSyncConfigStore
import com.riffle.core.domain.CommitSourceResult
import com.riffle.core.domain.DispatcherProvider
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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `commonTest` counterpart for the `awaitFirstLoad` / `observe()` tests in
 * `AnnotationSyncTargetHolderTest` (androidHostTest). The androidHostTest uses OkHttp; this suite
 * uses a Ktor MockEngine so the same state-machine assertions run on the iOS simulator in CI via
 * `:core:data:iosSimulatorArm64Test`.
 *
 * Each test creates the holder with `backgroundScope` so the holder's init coroutine shares the
 * `runTest` scheduler. Using an independent `CoroutineScope(UnconfinedTestDispatcher())` would put
 * the holder's coroutines on a different scheduler, making `awaitFirstLoad()` deadlock.
 */
class AnnotationSyncTargetHolderStartupTest {

    private val mockHttpClient = HttpClient(MockEngine { respond(ByteArray(0), HttpStatusCode.OK, headersOf()) })
    private val testDispatchers = object : DispatcherProvider {
        override val main = kotlinx.coroutines.Dispatchers.Default
        override val mainImmediate = kotlinx.coroutines.Dispatchers.Default
        override val io = kotlinx.coroutines.Dispatchers.Default
        override val default = kotlinx.coroutines.Dispatchers.Default
    }
    private val webDavFactory = WebDavAnnotationSyncTargetFactory(mockHttpClient, testDispatchers)

    private fun makeHolder(
        scope: CoroutineScope,
        configStore: HolderStartupFakeConfigStore = HolderStartupFakeConfigStore(),
        sourceRepository: SourceRepository = HolderStartupEmptySourceRepository(),
        tokenStorage: TokenStorage = HolderStartupNoopTokenStorage(),
    ) = AnnotationSyncTargetHolder(
        configStore = configStore,
        webDavFactory = webDavFactory,
        absBookmarkFactory = AbsBookmarkAnnotationSyncTargetFactory(
            absBookmarkApi = HolderStartupNoopAbsApi(),
            tokenStorage = tokenStorage,
        ),
        sourceRepository = sourceRepository,
        scope = scope,
    )

    @Test
    fun awaitFirstLoadResolvesAfterInitCoroutineFires() = runTest {
        val holder = makeHolder(scope = backgroundScope)
        var completed = false
        val job = launch { holder.awaitFirstLoad(); completed = true }
        job.join()
        assertTrue(completed, "awaitFirstLoad must complete after the init coroutine's first emission")
    }

    @Test
    fun awaitFirstLoadIsIdempotent() = runTest {
        val holder = makeHolder(scope = backgroundScope)
        holder.awaitFirstLoad()
        var completed = false
        val job = launch { holder.awaitFirstLoad(); completed = true }
        job.join()
        assertTrue(completed)
    }

    @Test
    fun observeEmitsNullInitiallyWhenNoConfigAndNoSources() = runTest {
        val holder = makeHolder(scope = backgroundScope)
        holder.awaitFirstLoad()
        assertNull(holder.observe().value, "no config and no sources => null target")
    }

    @Test
    fun observeEmitsNonNullAfterConfigIsSaved() = runTest {
        val configStore = HolderStartupFakeConfigStore()
        val holder = makeHolder(scope = backgroundScope, configStore = configStore)
        holder.awaitFirstLoad()
        configStore.emit(AnnotationSyncConfig("https://dav.example.org/anno", "u", "p"))
        // Suspend until the holder's init coroutine processes the new config and updates state.
        val target = holder.observe().first { it != null }
        assertNotNull(target, "config saved => non-null target")
    }

    @Test
    fun observeTransitionsNullToNonNullWhenAbsSourceAdded() = runTest {
        val mutableSources = HolderStartupMutableSourceRepository()
        val tokenStorage = HolderStartupInMemoryTokenStorage()
        val holder = makeHolder(
            scope = backgroundScope,
            sourceRepository = mutableSources,
            tokenStorage = tokenStorage,
        )

        holder.awaitFirstLoad()
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
        val target = holder.observe().first { it != null }

        assertNotNull(target, "after abs source added with a token, target must be non-null")
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

        val holder = makeHolder(
            scope = backgroundScope,
            sourceRepository = mutableSources,
            tokenStorage = tokenStorage,
        )
        // Suspend until the initial non-null state is confirmed.
        val initialTarget = holder.observe().first { it != null }
        assertNotNull(initialTarget, "source present => non-null target")

        mutableSources.emit(emptyList())
        // Suspend until the holder transitions back to null.
        val removedTarget = holder.observe().first { it == null }
        assertNull(removedTarget, "source removed, no WebDAV config => null target")
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
