package com.riffle.core.data

import com.riffle.core.common.FileStore
import com.riffle.core.data.testing.TestApplicationScope
import com.riffle.core.domain.CommitSourceResult
import com.riffle.core.domain.DispatcherProvider
import com.riffle.core.domain.PendingSource
import com.riffle.core.domain.SourceRepository
import com.riffle.core.domain.TokenStorage
import com.riffle.core.models.Source
import com.riffle.core.models.SourceUrl
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * Shared behaviour of [ReadaloudSidecarStore] (ADR 0040) over an okio [FakeFileSystem]: retry/
 * backoff, Failed-state no-retry, LRU eviction, purge-on-init of clipless sidecars, and
 * listCached/purgeSource semantics. Runs on both JVM and iOS (`iosSimulatorArm64Test`), so the same
 * assertions cover the shared state machine on both platforms.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ReadaloudSidecarStoreTest {

    private val mutableClock = object : Clock {
        var millis = 1_000L
        override fun now(): Instant = Instant.fromEpochMilliseconds(millis)
    }
    private val fakeFs = FakeFileSystem(mutableClock)

    private val dispatcher = StandardTestDispatcher()
    private val testScope = TestScope(dispatcher)

    private val testDispatchers = object : DispatcherProvider {
        override val main: CoroutineDispatcher = dispatcher
        override val mainImmediate: CoroutineDispatcher = dispatcher
        override val io: CoroutineDispatcher = dispatcher
        override val default: CoroutineDispatcher = dispatcher
    }

    private val source = Source("srv", SourceUrl.parse("http://storyteller")!!, false, false, "")

    private val sourceRepository = object : SourceRepository {
        override fun observeAll(): Flow<List<Source>> = flowOf(listOf(source))
        override suspend fun getActive(): Source? = source
        override suspend fun getById(sourceId: String): Source? = if (sourceId == "srv") source else null
        override suspend fun commit(pending: PendingSource, hiddenLibraryIds: Set<String>): CommitSourceResult = throw UnsupportedOperationException()
        override suspend fun setActive(sourceId: String) = Unit
        override suspend fun remove(sourceId: String) = Unit
        override suspend fun getSourceVersion(sourceId: String): String? = null
    }

    private val tokenStorage = object : TokenStorage {
        override suspend fun getToken(sourceId: String): String? = "tok"
        override suspend fun saveToken(sourceId: String, token: String) = Unit
        override suspend fun deleteToken(sourceId: String) = Unit
    }

    private val validSidecar = byteArrayOf(1, 2, 3, 4)

    private inner class FakeFsFileStore : FileStore {
        override fun resolve(namespace: String, relativePath: String): String {
            val base = "/riffle/$namespace"
            fakeFs.createDirectories(base.toPath())
            return if (relativePath.isEmpty()) base else "$base/$relativePath"
        }

        override fun exists(namespace: String, relativePath: String): Boolean =
            fakeFs.exists(resolve(namespace, relativePath).toPath())
    }

    private fun store(
        fetcher: StorytellerSidecarFetcher,
        clipInspector: SidecarClipInspector = SidecarClipInspector { true },
    ) = ReadaloudSidecarStore(
        fetcher = fetcher,
        clipInspector = clipInspector,
        sourceRepository = sourceRepository,
        tokenStorage = tokenStorage,
        fileStore = FakeFsFileStore(),
        applicationScope = TestApplicationScope(testScope),
        dispatchers = testDispatchers,
        fileSystem = fakeFs,
    )

    private fun fetcher(block: (attempt: Int) -> ByteArray?): StorytellerSidecarFetcher =
        object : StorytellerSidecarFetcher {
            private var attempt = 0
            override suspend fun fetch(baseUrl: String, bookId: String, token: String, insecureAllowed: Boolean): StorytellerSidecarFetcher.FetchResult =
                block(attempt++)?.let { StorytellerSidecarFetcher.FetchResult.Success(it) }
                    ?: StorytellerSidecarFetcher.FetchResult.NetworkError
        }

    private fun seedSidecar(sourceId: String, bookId: String, bytes: ByteArray, mtimeMillis: Long? = null) {
        val path = "/riffle/$NS_READALOUD_SIDECARS/$sourceId/$bookId.epub".toPath()
        fakeFs.createDirectories(path.parent!!)
        mtimeMillis?.let { mutableClock.millis = it }
        fakeFs.write(path) { write(bytes) }
    }

    // --- basic success ---

    @Test
    fun `prepare succeeds on first attempt`() = testScope.runTest {
        val store = store(fetcher { validSidecar })
        store.prepare("srv", "42")
        advanceUntilIdle()
        assertEquals(ReadaloudSidecarStore.State.Ready, store.stateOf("srv", "42"))
        assertNotNull(store.cachedPath("srv", "42"))
    }

    // --- retry: success on later attempt ---

    @Test
    fun `retries after one failure and succeeds on second attempt`() = testScope.runTest {
        val store = store(fetcher { attempt -> if (attempt < 1) null else validSidecar })
        store.prepare("srv", "42")
        advanceUntilIdle()
        assertEquals(ReadaloudSidecarStore.State.Ready, store.stateOf("srv", "42"))
        assertNotNull(store.cachedPath("srv", "42"))
    }

    @Test
    fun `retries after two failures and succeeds on third attempt`() = testScope.runTest {
        val store = store(fetcher { attempt -> if (attempt < 2) null else validSidecar })
        store.prepare("srv", "42")
        advanceUntilIdle()
        assertEquals(ReadaloudSidecarStore.State.Ready, store.stateOf("srv", "42"))
        assertNotNull(store.cachedPath("srv", "42"))
    }

    // --- retry: all attempts exhausted ---

    @Test
    fun `fails permanently after all four attempts exhausted`() = testScope.runTest {
        val store = store(fetcher { null })
        store.prepare("srv", "42")
        advanceUntilIdle()
        assertEquals(ReadaloudSidecarStore.State.Failed, store.stateOf("srv", "42"))
        assertNull(store.cachedPath("srv", "42"))
    }

    // --- state during retry ---

    @Test
    fun `state is Preparing immediately after prepare is called`() = testScope.runTest {
        val store = store(fetcher { validSidecar })
        store.prepare("srv", "42")
        // Without advancing the dispatcher, the background coroutine hasn't run yet — but prepare()
        // must have set Preparing synchronously so the UI sees it before the first tick.
        assertEquals(ReadaloudSidecarStore.State.Preparing, store.stateOf("srv", "42"))
    }

    @Test
    fun `state stays Preparing during retry backoff`() = testScope.runTest {
        val store = store(fetcher { attempt -> if (attempt < 1) null else validSidecar })
        store.prepare("srv", "42")
        // Run the initial launch and the first (failing) fetch, then stop just inside the backoff delay.
        dispatcher.scheduler.advanceTimeBy(1L)
        assertEquals(ReadaloudSidecarStore.State.Preparing, store.stateOf("srv", "42"))
    }

    @Test
    fun `prepare fails immediately without retrying when fetch returns NotAligned`() = testScope.runTest {
        var fetchCount = 0
        val store = store(
            object : StorytellerSidecarFetcher {
                override suspend fun fetch(baseUrl: String, bookId: String, token: String, insecureAllowed: Boolean): StorytellerSidecarFetcher.FetchResult {
                    fetchCount++
                    return StorytellerSidecarFetcher.FetchResult.NotAligned
                }
            },
        )
        store.prepare("srv", "42")
        advanceUntilIdle()
        assertEquals(1, fetchCount) // single attempt, no retries
        assertEquals(ReadaloudSidecarStore.State.Failed, store.stateOf("srv", "42"))
        assertNull(store.cachedPath("srv", "42"))
    }

    @Test
    fun `prepare does not relaunch after all attempts exhausted`() = testScope.runTest {
        var fetchCount = 0
        val store = store(
            fetcher {
                fetchCount++
                null
            },
        )
        store.prepare("srv", "42")
        advanceUntilIdle()
        assertEquals(ReadaloudSidecarStore.State.Failed, store.stateOf("srv", "42"))
        val countAfterFirstCycle = fetchCount // should be MAX_RETRIES + 1 = 4

        // A second prepare() must be a no-op — no more fetches, state stays Failed.
        store.prepare("srv", "42")
        advanceUntilIdle()
        assertEquals(countAfterFirstCycle, fetchCount)
        assertEquals(ReadaloudSidecarStore.State.Failed, store.stateOf("srv", "42"))
    }

    // --- purge on source removal ---

    @Test
    fun `purgeSource deletes only files owned by that source`() = testScope.runTest {
        val store = store(fetcher { validSidecar })
        seedSidecar("srv", "1", byteArrayOf(1, 2, 3))
        seedSidecar("srv", "2", byteArrayOf(1, 2, 3))
        seedSidecar("other", "9", byteArrayOf(1, 2, 3))

        store.purgeSource("srv")

        assertNull(store.cachedPath("srv", "1"))
        assertNull(store.cachedPath("srv", "2"))
        assertNotNull(store.cachedPath("other", "9"))
    }

    // --- purge on init: clipless sidecars are unplayable and must be dropped ---

    @Test
    fun `purge on init deletes a cached sidecar with no clips`() = testScope.runTest {
        seedSidecar("srv", "1", byteArrayOf(9, 9, 9))
        seedSidecar("srv", "2", byteArrayOf(8, 8, 8))

        // Inspector says book 1 has no clips (unplayable) but book 2 is fine.
        val store = store(
            fetcher { validSidecar },
            clipInspector = SidecarClipInspector { bytes -> !bytes.contentEquals(byteArrayOf(9, 9, 9)) },
        )
        advanceUntilIdle()

        assertNull(store.cachedPath("srv", "1"))
        assertNotNull(store.cachedPath("srv", "2"))
    }

    // --- listCached ---

    @Test
    fun `listCached returns every cached sidecar keyed by source and book`() = testScope.runTest {
        val store = store(fetcher { validSidecar })
        seedSidecar("srv", "1", byteArrayOf(1, 2, 3))
        seedSidecar("other", "9", byteArrayOf(1, 2, 3, 4, 5))

        val cached = store.listCached().associateBy { it.storytellerSourceId to it.storytellerBookId }

        assertEquals(2, cached.size)
        assertEquals(3L, cached["srv" to "1"]?.sizeBytes)
        assertEquals(5L, cached["other" to "9"]?.sizeBytes)
    }

    // --- LRU eviction ---

    @Test
    fun `enforceLruBudget evicts oldest files first once cap is exceeded`() = testScope.runTest {
        val store = store(fetcher { validSidecar })
        // Three 100-byte files with strictly-increasing mtimes. Cap of 250 must evict exactly one
        // (the oldest) to bring total from 300 → 200.
        seedSidecar("srv", "1", ByteArray(100), mtimeMillis = 1_000)
        seedSidecar("srv", "2", ByteArray(100), mtimeMillis = 2_000)
        seedSidecar("srv", "3", ByteArray(100), mtimeMillis = 3_000)

        val evicted = store.enforceLruBudget(capBytes = 250)

        assertEquals(setOf(store.key("srv", "1")), evicted)
        assertNull(store.cachedPath("srv", "1"))
        assertNotNull(store.cachedPath("srv", "2"))
        assertNotNull(store.cachedPath("srv", "3"))
    }

    @Test
    fun `enforceLruBudget is a no-op when total is under cap`() = testScope.runTest {
        val store = store(fetcher { validSidecar })
        seedSidecar("srv", "1", ByteArray(50))
        seedSidecar("srv", "2", ByteArray(50))

        val evicted = store.enforceLruBudget(capBytes = 1_000)

        assertEquals(emptySet(), evicted)
    }
}
