package com.riffle.core.data

import com.riffle.core.data.absbookmark.AbsBookmarkAnnotationSyncTargetFactory
import com.riffle.core.domain.AnnotationSyncConfig
import com.riffle.core.domain.AnnotationSyncConfigStore
import com.riffle.core.domain.CommitSourceResult
import com.riffle.core.domain.PendingSource
import com.riffle.core.domain.Source
import com.riffle.core.domain.SourceRepository
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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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
