package com.riffle.core.data

import com.riffle.core.domain.AuthenticateResult
import com.riffle.core.domain.CommitServerResult
import com.riffle.core.domain.PendingServer
import com.riffle.core.domain.Server
import com.riffle.core.domain.ServerRepository
import com.riffle.core.domain.ServerType
import com.riffle.core.domain.ServerUrl
import com.riffle.core.domain.TokenStorage
import com.riffle.core.network.NetworkStorytellerBundleResult
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

    private val server = Server("srv", ServerUrl.parse("http://storyteller")!!, false, false, "")

    private val serverRepository = object : ServerRepository {
        override fun observeAll(): Flow<List<Server>> = flowOf(listOf(server))
        override suspend fun getActive(): Server? = server
        override suspend fun getById(serverId: String): Server? = if (serverId == "srv") server else null
        override suspend fun authenticate(url: ServerUrl, username: String, password: String, insecureAllowed: Boolean, serverType: ServerType): AuthenticateResult = throw UnsupportedOperationException()
        override suspend fun commit(pending: PendingServer, hiddenLibraryIds: Set<String>): CommitServerResult = throw UnsupportedOperationException()
        override suspend fun setActive(serverId: String) = Unit
        override suspend fun remove(serverId: String) = Unit
        override suspend fun getServerVersion(serverId: String): String? = null
    }
    private val tokenStorage = object : TokenStorage {
        override suspend fun getToken(serverId: String): String? = "tok"
        override suspend fun saveToken(serverId: String, token: String) = Unit
        override suspend fun deleteToken(serverId: String) = Unit
    }

    private val validSidecar = zipOf(
        "OEBPS/content.opf" to "<package/>".toByteArray(),
        "MediaOverlays/c1.smil" to "<smil/>".toByteArray(),
        "text/c1.html" to "<html/>".toByteArray(),
    )

    private fun store(fetcher: StorytellerSidecarFetcher) = ReadaloudSidecarStore(
        cacheRootDir = tmp.root,
        fetcher = fetcher,
        serverRepository = serverRepository,
        tokenStorage = tokenStorage,
        scope = testScope,
    )

    private fun fetcher(block: (attempt: Int) -> ByteArray?): StorytellerSidecarFetcher {
        var attempt = 0
        return object : StorytellerSidecarFetcher(StorytellerBundleApi { _, _, _, _ ->
            error("unreachable in fake")
        }) {
            override suspend fun fetch(baseUrl: String, bookId: String, token: String, insecureAllowed: Boolean): FetchResult =
                block(attempt++)?.let { FetchResult.Success(it) } ?: FetchResult.NetworkError
        }
    }

    private fun notAlignedFetcher(): StorytellerSidecarFetcher {
        return object : StorytellerSidecarFetcher(StorytellerBundleApi { _, _, _, _ ->
            error("unreachable in fake")
        }) {
            override suspend fun fetch(baseUrl: String, bookId: String, token: String, insecureAllowed: Boolean): FetchResult =
                FetchResult.NotAligned
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
        val store = store(object : StorytellerSidecarFetcher(StorytellerBundleApi { _, _, _, _ ->
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

    // --- helpers ---

    private fun zipOf(vararg entries: Pair<String, ByteArray>): ByteArray {
        val bos = ByteArrayOutputStream()
        ZipOutputStream(bos).use { zos ->
            for ((name, bytes) in entries) { zos.putNextEntry(ZipEntry(name)); zos.write(bytes); zos.closeEntry() }
        }
        return bos.toByteArray()
    }
}
