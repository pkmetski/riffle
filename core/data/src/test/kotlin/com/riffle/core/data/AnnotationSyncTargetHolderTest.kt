package com.riffle.core.data

import com.riffle.core.data.absbookmark.AbsBookmarkAnnotationSyncTargetFactory
import com.riffle.core.data.absbookmark.CompositeAnnotationSyncTarget
import com.riffle.core.domain.AbsWebSourceDescriptor
import com.riffle.core.domain.AnnotationSyncConfig
import com.riffle.core.domain.AnnotationSyncConfigStore
import com.riffle.core.domain.CommitSourceResult
import com.riffle.core.domain.PendingSource
import com.riffle.core.domain.SourceRepository
import com.riffle.core.models.ServerType
import com.riffle.core.models.Source
import com.riffle.core.models.SourceType
import com.riffle.core.models.SourceUrl
import com.riffle.core.domain.TokenStorage
import com.riffle.core.network.AbsBookmarkApi
import com.riffle.core.network.NetworkAbsBookmark
import com.riffle.core.network.NetworkResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The holder observes [AnnotationSyncConfigStore] and rebuilds the [AnnotationSyncTarget] on
 * every change. UnconfinedTestDispatcher makes the launched collector run inline so we can
 * verify state synchronously after each emission without juggling delays.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AnnotationSyncTargetHolderTest {

    private val configStore = FakeConfigStore()
    private val factory = WebDavAnnotationSyncTargetFactory(OkHttpClient(), com.riffle.core.domain.DefaultDispatcherProvider)
    private val emptySources = EmptySourceRepository()
    private val absBookmarkFactory = AbsBookmarkAnnotationSyncTargetFactory(
        absBookmarkApi = HolderTestNoopAbsBookmarkApi(),
        tokenStorage = NoopTokenStorage(),
    )
    private lateinit var scope: CoroutineScope

    private fun holder() = AnnotationSyncTargetHolder(
        configStore = configStore,
        webDavFactory = factory,
        absBookmarkFactory = absBookmarkFactory,
        sourceRepository = emptySources,
        scope = scope,
    )

    @Before
    fun setUp() {
        scope = CoroutineScope(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        scope.cancel()
    }

    @Test
    fun `current is null when no config has been saved`() = runTest {
        val holder = holder()

        assertNull(holder.current())
    }

    @Test
    fun `saving a valid config rebuilds the target`() = runTest {
        val holder = holder()

        configStore.emit(AnnotationSyncConfig("https://dav.example.org/anno", "alice", "pw"))

        assertNotNull("expected a non-null target after the first config", holder.current())
    }

    @Test
    fun `a malformed URL emits null target (factory returns null)`() = runTest {
        val holder = holder()

        configStore.emit(AnnotationSyncConfig("::not a url::", "u", "p"))

        assertNull(holder.current())
    }

    @Test
    fun `clearing the config releases the target back to null`() = runTest {
        val holder = holder()

        configStore.emit(AnnotationSyncConfig("https://dav.example.org/anno", "u", "p"))
        assertNotNull(holder.current())

        configStore.emit(null)

        assertNull(holder.current())
    }

    // Regression: for accounts served by an ABS-bookmark child (currently the allow-listed
    // `plamen` / `test` usernames), WebDAV steps aside so that account's annotations flow
    // over ABS bookmarks only — no dual-write to WebDAV. Every other namespace (Komga, non-
    // allow-listed ABS accounts) continues to route to WebDAV as before. See
    // AnnotationSyncTargetHolder composition rules.
    @Test
    fun `webdav does not serve namespaces already covered by an abs-bookmark child`() = runTest {
        val absSource = Source(
            id = "src-plamen",
            url = SourceUrl.parse("http://abs.local")!!,
            isActive = true,
            insecureConnectionAllowed = false,
            username = "plamen",
            type = SourceType.ABS,
            serverType = ServerType.AUDIOBOOKSHELF,
            absUserId = "abs-user-1",
        )
        val sources = SingleSourceRepository(absSource)
        val tokenStorage = InMemoryTokenStorage().apply { savedTokens[absSource.id] = "tok" }
        val absFactory = AbsBookmarkAnnotationSyncTargetFactory(
            absBookmarkApi = HolderTestNoopAbsBookmarkApi(),
            tokenStorage = tokenStorage,
        )
        val holder = AnnotationSyncTargetHolder(
            configStore = configStore,
            webDavFactory = factory,
            absBookmarkFactory = absFactory,
            sourceRepository = sources,
            scope = scope,
        )
        configStore.emit(AnnotationSyncConfig("https://dav.example.org/anno", "u", "p"))

        val composite = holder.current() as? CompositeAnnotationSyncTarget
            ?: error("expected a Composite target when both WebDAV and an ABS child are configured")

        val absNamespace = "${AbsWebSourceDescriptor.ABS_NAMESPACE_PREFIX}${absSource.absUserId}"
        assertEquals(
            "ABS-served namespace must route to the ABS child only",
            listOf("abs:${absSource.id.take(8)}"),
            composite.eligibleLabels(absNamespace),
        )
        assertTrue(
            "an unrelated namespace (e.g. Komga) must still route to WebDAV",
            composite.eligibleLabels("komga_other").contains("webdav"),
        )
    }

    @Test
    fun `a subsequent config change replaces the previous target`() = runTest {
        val holder = holder()

        configStore.emit(AnnotationSyncConfig("https://first.example.org/anno", "u", "p"))
        val first = holder.current()
        configStore.emit(AnnotationSyncConfig("https://second.example.org/anno", "u", "p"))
        val second = holder.current()

        assertNotNull(first)
        assertNotNull(second)
        // Different config → different target instance.
        assert(first !== second) { "second config should produce a fresh target" }
    }
}

private class FakeConfigStore : AnnotationSyncConfigStore {
    private val state = MutableStateFlow<AnnotationSyncConfig?>(null)
    override fun observe(): StateFlow<AnnotationSyncConfig?> = state
    override suspend fun save(config: AnnotationSyncConfig) { state.value = config }
    override suspend fun clear() { state.value = null }
    fun emit(value: AnnotationSyncConfig?) { state.value = value }
}

private class EmptySourceRepository : SourceRepository {
    private val state = MutableStateFlow<List<Source>>(emptyList())
    override fun observeAll(): Flow<List<Source>> = state
    override suspend fun getActive(): Source? = null
    override suspend fun commit(pending: PendingSource, hiddenLibraryIds: Set<String>): CommitSourceResult =
        CommitSourceResult.Failure(UnsupportedOperationException())
    override suspend fun setActive(sourceId: String) {}
    override suspend fun remove(sourceId: String) {}
    override suspend fun getSourceVersion(sourceId: String): String? = null
}

private class NoopTokenStorage : TokenStorage {
    override suspend fun saveToken(sourceId: String, token: String) {}
    override suspend fun getToken(sourceId: String): String? = null
    override suspend fun deleteToken(sourceId: String) {}
}

private class InMemoryTokenStorage : TokenStorage {
    val savedTokens: MutableMap<String, String> = mutableMapOf()
    override suspend fun saveToken(sourceId: String, token: String) { savedTokens[sourceId] = token }
    override suspend fun getToken(sourceId: String): String? = savedTokens[sourceId]
    override suspend fun deleteToken(sourceId: String) { savedTokens.remove(sourceId) }
}

private class SingleSourceRepository(private val source: Source) : SourceRepository {
    private val state = MutableStateFlow(listOf(source))
    override fun observeAll(): Flow<List<Source>> = state
    override suspend fun getActive(): Source? = source
    override suspend fun commit(pending: PendingSource, hiddenLibraryIds: Set<String>): CommitSourceResult =
        CommitSourceResult.Failure(UnsupportedOperationException())
    override suspend fun setActive(sourceId: String) {}
    override suspend fun remove(sourceId: String) {}
    override suspend fun getSourceVersion(sourceId: String): String? = null
}

private class HolderTestNoopAbsBookmarkApi : AbsBookmarkApi {
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
