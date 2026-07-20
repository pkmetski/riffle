package com.riffle.core.data

import com.riffle.core.network.NetworkResult

import com.riffle.core.domain.AuthenticateResult
import com.riffle.core.domain.CommitSourceResult
import com.riffle.core.domain.PendingSource
import com.riffle.core.models.Source
import com.riffle.core.domain.SourceRepository
import com.riffle.core.models.ServerType
import com.riffle.core.models.SourceUrl
import com.riffle.core.domain.TokenStorage
import com.riffle.core.network.StorytellerBundleApi
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@OptIn(ExperimentalCoroutinesApi::class)
class ReadaloudSidecarStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val dispatcher = StandardTestDispatcher()
    private val testScope = TestScope(dispatcher)

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

    private val validSidecar = zipOf(
        "OEBPS/content.opf" to "<package/>".toByteArray(),
        "MediaOverlays/c1.smil" to "<smil/>".toByteArray(),
        "text/c1.html" to "<html/>".toByteArray(),
    )

    private fun store(fetcher: StorytellerSidecarFetcher) = ReadaloudSidecarStore(
        cacheRootDir = tmp.root,
        fetcher = fetcher,
        sourceRepository = sourceRepository,
        tokenStorage = tokenStorage,
        scope = testScope,
    )

    private fun fetcher(block: (attempt: Int) -> ByteArray?): StorytellerSidecarFetcher {
        var attempt = 0
        return object : StorytellerSidecarFetcher(dispatchers = com.riffle.core.domain.DefaultDispatcherProvider, bundleApi = StorytellerBundleApi { _, _, _, _ ->
            error("unreachable in fake")
        }) {
            override suspend fun fetch(baseUrl: String, bookId: String, token: String, insecureAllowed: Boolean): FetchResult =
                block(attempt++)?.let { FetchResult.Success(it) } ?: FetchResult.NetworkError
        }
    }

    // --- basic success ---

    @Test
    fun `prepare succeeds on first attempt`() = testScope.runTest {
        val store = store(fetcher { validSidecar })
        store.prepare("srv", "42")
        advanceUntilIdle()
        assertEquals(ReadaloudSidecarStore.State.Ready, store.stateOf("srv", "42"))
        assertNotNull(store.cachedFile("srv", "42"))
    }

    // --- retry: success on later attempt ---

    @Test
    fun `retries after one failure and succeeds on second attempt`() = testScope.runTest {
        val store = store(fetcher { attempt -> if (attempt < 1) null else validSidecar })
        store.prepare("srv", "42")
        advanceUntilIdle()
        assertEquals(ReadaloudSidecarStore.State.Ready, store.stateOf("srv", "42"))
        assertNotNull(store.cachedFile("srv", "42"))
    }

    @Test
    fun `retries after two failures and succeeds on third attempt`() = testScope.runTest {
        val store = store(fetcher { attempt -> if (attempt < 2) null else validSidecar })
        store.prepare("srv", "42")
        advanceUntilIdle()
        assertEquals(ReadaloudSidecarStore.State.Ready, store.stateOf("srv", "42"))
        assertNotNull(store.cachedFile("srv", "42"))
    }

    // --- retry: all attempts exhausted ---

    @Test
    fun `fails permanently after all four attempts exhausted`() = testScope.runTest {
        val store = store(fetcher { null })
        store.prepare("srv", "42")
        advanceUntilIdle()
        assertEquals(ReadaloudSidecarStore.State.Failed, store.stateOf("srv", "42"))
        assertNull(store.cachedFile("srv", "42"))
    }

    // --- state during retry ---

    @Test
    fun `state is Preparing immediately after prepare() is called`() = testScope.runTest {
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
        val store = store(object : StorytellerSidecarFetcher(dispatchers = com.riffle.core.domain.DefaultDispatcherProvider, bundleApi = StorytellerBundleApi { _, _, _, _ ->
            error("unreachable in fake")
        }) {
            override suspend fun fetch(baseUrl: String, bookId: String, token: String, insecureAllowed: Boolean): FetchResult =
                FetchResult.NotAligned.also { fetchCount++ }
        })
        store.prepare("srv", "42")
        advanceUntilIdle()
        assertEquals(1, fetchCount)  // single attempt, no retries
        assertEquals(ReadaloudSidecarStore.State.Failed, store.stateOf("srv", "42"))
        assertNull(store.cachedFile("srv", "42"))
    }

    @Test
    fun `prepare does not relaunch after all attempts exhausted`() = testScope.runTest {
        var fetchCount = 0
        val store = store(fetcher { fetchCount++; null })
        store.prepare("srv", "42")
        advanceUntilIdle()
        assertEquals(ReadaloudSidecarStore.State.Failed, store.stateOf("srv", "42"))
        val countAfterFirstCycle = fetchCount   // should be MAX_RETRIES + 1 = 4

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
        val dir = java.io.File(tmp.root, "readaloud-sidecars").apply { mkdirs() }
        // Two sources, three books total.
        java.io.File(dir, "srv-1.epub").writeBytes(byteArrayOf(1, 2, 3))
        java.io.File(dir, "srv-2.epub").writeBytes(byteArrayOf(1, 2, 3))
        java.io.File(dir, "other-9.epub").writeBytes(byteArrayOf(1, 2, 3))

        store.purgeSource("srv")

        assertNull(store.cachedFile("srv", "1"))
        assertNull(store.cachedFile("srv", "2"))
        assertNotNull(store.cachedFile("other", "9"))
    }

    // --- LRU eviction ---

    @Test
    fun `enforceLruBudget evicts oldest files first once cap is exceeded`() = testScope.runTest {
        val store = store(fetcher { validSidecar })
        val dir = java.io.File(tmp.root, "readaloud-sidecars").apply { mkdirs() }
        // Three 100-byte files with strictly-increasing mtimes. Cap of 250 must evict exactly one
        // (the oldest) to bring total from 300 → 200.
        val oldest = java.io.File(dir, "srv-1.epub").apply { writeBytes(ByteArray(100)) }
        val middle = java.io.File(dir, "srv-2.epub").apply { writeBytes(ByteArray(100)) }
        val newest = java.io.File(dir, "srv-3.epub").apply { writeBytes(ByteArray(100)) }
        oldest.setLastModified(1_000)
        middle.setLastModified(2_000)
        newest.setLastModified(3_000)

        val evicted = store.enforceLruBudget(capBytes = 250)

        // Oldest goes first. Once we're back under cap, no more evictions.
        assertEquals(setOf("srv-1"), evicted)
        assertEquals(false, oldest.exists())
        assertEquals(true, middle.exists())
        assertEquals(true, newest.exists())
    }

    @Test
    fun `enforceLruBudget is a no-op when total is under cap`() = testScope.runTest {
        val store = store(fetcher { validSidecar })
        val dir = java.io.File(tmp.root, "readaloud-sidecars").apply { mkdirs() }
        java.io.File(dir, "srv-1.epub").writeBytes(ByteArray(50))
        java.io.File(dir, "srv-2.epub").writeBytes(ByteArray(50))

        val evicted = store.enforceLruBudget(capBytes = 1_000)

        assertEquals(emptySet<String>(), evicted)
    }

    // --- helpers ---

    private fun zipOf(vararg entries: Pair<String, ByteArray>): ByteArray {
        val bos = ByteArrayOutputStream()
        ZipOutputStream(bos).use { zos ->
            for ((name, bytes) in entries) { zos.putNextEntry(ZipEntry(name)); zos.write(bytes); zos.closeEntry() }
        }
        return bos.toByteArray()
    }
}
