package com.riffle.core.data

import com.riffle.core.domain.AuthenticateResult
import com.riffle.core.domain.CommitServerResult
import com.riffle.core.domain.PendingServer
import com.riffle.core.domain.ProgressSyncCycleResult
import com.riffle.core.domain.ReadingPositionStore
import com.riffle.core.domain.Server
import com.riffle.core.domain.ServerRepository
import com.riffle.core.domain.ServerUrl
import com.riffle.core.domain.SessionPayload
import com.riffle.core.domain.TokenStorage
import com.riffle.core.network.AbsSessionApi
import com.riffle.core.network.NetworkEbookProgressPayload
import com.riffle.core.network.NetworkGetProgressResult
import com.riffle.core.network.NetworkServerProgress
import com.riffle.core.network.NetworkSyncSessionResult
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class ProgressSyncCycleTest {

    // --- fakes ---

    private class FakePositionStore(var localUpdatedAt: Long = 0L) : ReadingPositionStore {
        var updatedTimestamp: Long? = null
        var updatedServerId: String? = null
        override suspend fun save(serverId: String, itemId: String, cfi: String) = Unit
        override suspend fun load(serverId: String, itemId: String): String? = null
        override suspend fun loadLocalUpdatedAt(serverId: String, itemId: String): Long = localUpdatedAt
        override suspend fun updateLocalTimestamp(serverId: String, itemId: String, millis: Long) {
            updatedServerId = serverId
            updatedTimestamp = millis
        }
    }

    private class FakeSessionApi(
        private val getResult: NetworkGetProgressResult,
        private val patchResult: NetworkSyncSessionResult = NetworkSyncSessionResult.Success(0L),
    ) : AbsSessionApi {
        var patchCallCount = 0
        override suspend fun syncEbookProgress(
            baseUrl: String, libraryItemId: String, payload: NetworkEbookProgressPayload,
            token: String, insecureAllowed: Boolean,
        ): NetworkSyncSessionResult {
            patchCallCount++
            return patchResult
        }
        override suspend fun getProgress(
            baseUrl: String, libraryItemId: String, token: String, insecureAllowed: Boolean,
        ): NetworkGetProgressResult = getResult
    }

    private fun buildRepo(api: AbsSessionApi, positionStore: ReadingPositionStore) =
        ReadingSessionRepositoryImpl(
            api = api,
            serverRepository = object : ServerRepository {
                val server = Server("s1", ServerUrl.parse("http://localhost")!!, "T", true, false, "")
                override fun observeAll(): Flow<List<Server>> = flowOf(listOf(server))
                override suspend fun getActive(): Server = server
                override suspend fun authenticate(url: ServerUrl, username: String, password: String, insecureAllowed: Boolean): AuthenticateResult = throw UnsupportedOperationException()
                override suspend fun commit(pending: PendingServer, hiddenLibraryIds: Set<String>): CommitServerResult = throw UnsupportedOperationException()
                override suspend fun setActive(serverId: String) = Unit
                override suspend fun remove(serverId: String) = Unit
                override suspend fun getServerVersion(serverId: String): String? = null
            },
            tokenStorage = object : TokenStorage {
                override suspend fun saveToken(serverId: String, token: String) = Unit
                override suspend fun getToken(serverId: String): String? = "tok"
                override suspend fun deleteToken(serverId: String) = Unit
            },
            positionStore = positionStore,
        )

    private val payload = SessionPayload("epubcfi(/6/4!/4/1:0)", 0.25f)

    // --- tests ---

    @Test
    fun `server-newer returns ServerWins with server ebookLocation and updates localUpdatedAt`() = runTest {
        val localTs = 1_000L
        val serverTs = 2_000L
        val positionStore = FakePositionStore(localUpdatedAt = localTs)
        val api = FakeSessionApi(
            getResult = NetworkGetProgressResult.Success(
                NetworkServerProgress("epubcfi(/6/8!/4/1:0)", lastUpdate = serverTs)
            )
        )

        val result = buildRepo(api, positionStore).runSyncCycle("item-1", payload)

        assertTrue(result is ProgressSyncCycleResult.ServerWins)
        assertEquals("epubcfi(/6/8!/4/1:0)", (result as ProgressSyncCycleResult.ServerWins).serverProgress.ebookLocation)
        assertEquals(serverTs, result.serverProgress.lastUpdate)
        assertEquals(serverTs, positionStore.updatedTimestamp)
        assertEquals(0, api.patchCallCount)
    }

    @Test
    fun `local-newer sends PATCH and returns LocalWins, updates localUpdatedAt from response`() = runTest {
        val localTs = 3_000L
        val serverTs = 1_000L
        val patchResponseTs = 3_100L
        val positionStore = FakePositionStore(localUpdatedAt = localTs)
        val api = FakeSessionApi(
            getResult = NetworkGetProgressResult.Success(NetworkServerProgress("old-cfi", lastUpdate = serverTs)),
            patchResult = NetworkSyncSessionResult.Success(patchResponseTs),
        )

        val result = buildRepo(api, positionStore).runSyncCycle("item-1", payload)

        assertTrue(result is ProgressSyncCycleResult.LocalWins)
        assertEquals(1, api.patchCallCount)
        assertEquals(patchResponseTs, positionStore.updatedTimestamp)
    }

    @Test
    fun `equal timestamps returns InSync without PATCH`() = runTest {
        val ts = 2_000L
        val positionStore = FakePositionStore(localUpdatedAt = ts)
        val api = FakeSessionApi(
            getResult = NetworkGetProgressResult.Success(NetworkServerProgress("cfi", lastUpdate = ts))
        )

        val result = buildRepo(api, positionStore).runSyncCycle("item-1", payload)

        assertTrue(result is ProgressSyncCycleResult.InSync)
        assertEquals(0, api.patchCallCount)
    }

    @Test
    fun `GET failure returns Offline without PATCH and leaves localUpdatedAt unchanged`() = runTest {
        val positionStore = FakePositionStore(localUpdatedAt = 5_000L)
        val api = FakeSessionApi(
            getResult = NetworkGetProgressResult.NetworkError(IOException("unreachable"))
        )

        val result = buildRepo(api, positionStore).runSyncCycle("item-1", payload)

        assertTrue(result is ProgressSyncCycleResult.Offline)
        assertEquals(0, api.patchCallCount)
        assertEquals(null, positionStore.updatedTimestamp)
    }

    @Test
    fun `local-newer with PATCH returning zero lastUpdate does NOT corrupt localUpdatedAt to zero`() = runTest {
        val localTs = 3_000L
        val positionStore = FakePositionStore(localUpdatedAt = localTs)
        val api = FakeSessionApi(
            getResult = NetworkGetProgressResult.Success(NetworkServerProgress("old-cfi", lastUpdate = 1_000L)),
            patchResult = NetworkSyncSessionResult.Success(0L),
        )

        buildRepo(api, positionStore).runSyncCycle("item-1", payload)

        assertTrue(positionStore.updatedTimestamp != null)
        assertTrue(positionStore.updatedTimestamp!! > 0L)
    }

    @Test
    fun `local-newer with PATCH returning positive lastUpdate sets localUpdatedAt correctly`() = runTest {
        val localTs = 3_000L
        val patchResponseTs = 3_100L
        val positionStore = FakePositionStore(localUpdatedAt = localTs)
        val api = FakeSessionApi(
            getResult = NetworkGetProgressResult.Success(NetworkServerProgress("old-cfi", lastUpdate = 1_000L)),
            patchResult = NetworkSyncSessionResult.Success(patchResponseTs),
        )

        buildRepo(api, positionStore).runSyncCycle("item-1", payload)

        assertEquals(patchResponseTs, positionStore.updatedTimestamp)
    }

    @Test
    fun `local-newer with zero PATCH lastUpdate still returns LocalWins`() = runTest {
        val positionStore = FakePositionStore(localUpdatedAt = 3_000L)
        val api = FakeSessionApi(
            getResult = NetworkGetProgressResult.Success(NetworkServerProgress("old-cfi", lastUpdate = 1_000L)),
            patchResult = NetworkSyncSessionResult.Success(0L),
        )

        val result = buildRepo(api, positionStore).runSyncCycle("item-1", payload)

        assertTrue(result is ProgressSyncCycleResult.LocalWins)
    }

    @Test
    fun `setProgress bumps localUpdatedAt and attempts PATCH`() = runTest {
        val positionStore = FakePositionStore(localUpdatedAt = 1_000L)
        val api = FakeSessionApi(
            getResult = NetworkGetProgressResult.NetworkError(IOException("unused")),
            patchResult = NetworkSyncSessionResult.Success(2_000L),
        )
        val repo = buildRepo(api, positionStore)

        repo.setProgress("item-1", 1.0f)

        assertEquals(1, api.patchCallCount)
        assertNotNull(positionStore.updatedTimestamp)
        assertTrue(positionStore.updatedTimestamp!! > 0L)
    }

    // --- 404-equivalent (server has no progress record) ---

    @Test
    fun `server returns no-progress (lastUpdate=0) with no local data returns InSync`() = runTest {
        // Both local and server have lastUpdate=0 — nothing to push or pull.
        val positionStore = FakePositionStore(localUpdatedAt = 0L)
        val api = FakeSessionApi(
            getResult = NetworkGetProgressResult.Success(NetworkServerProgress("", lastUpdate = 0L))
        )

        val result = buildRepo(api, positionStore).runSyncCycle("item-1", payload)

        assertTrue(result is ProgressSyncCycleResult.InSync)
        assertEquals(0, api.patchCallCount)
    }

    @Test
    fun `server returns no-progress (lastUpdate=0) with local data returns LocalWins and sends PATCH`() = runTest {
        // Server has no record (mapped 404), local has been read — must push.
        val positionStore = FakePositionStore(localUpdatedAt = 5_000L)
        val patchResponseTs = 5_100L
        val api = FakeSessionApi(
            getResult = NetworkGetProgressResult.Success(NetworkServerProgress("", lastUpdate = 0L)),
            patchResult = NetworkSyncSessionResult.Success(patchResponseTs),
        )

        val result = buildRepo(api, positionStore).runSyncCycle("item-1", payload)

        assertTrue(result is ProgressSyncCycleResult.LocalWins)
        assertEquals(1, api.patchCallCount)
        assertEquals(patchResponseTs, positionStore.updatedTimestamp)
    }

    @Test
    fun `server returns no-progress and ServerWins result carries ebookProgress field`() = runTest {
        val positionStore = FakePositionStore(localUpdatedAt = 0L)
        val api = FakeSessionApi(
            getResult = NetworkGetProgressResult.Success(
                NetworkServerProgress("epubcfi(/6/4!/4/2/1:5)", ebookProgress = 0.42f, lastUpdate = 9_000L)
            )
        )

        val result = buildRepo(api, positionStore).runSyncCycle("item-1", payload)

        assertTrue(result is ProgressSyncCycleResult.ServerWins)
        val serverProgress = (result as ProgressSyncCycleResult.ServerWins).serverProgress
        assertEquals("epubcfi(/6/4!/4/2/1:5)", serverProgress.ebookLocation)
        assertEquals(0.42f, serverProgress.ebookProgress, 0.001f)
        assertEquals(9_000L, serverProgress.lastUpdate)
    }

    @Test
    fun `setProgress bumps localUpdatedAt even when PATCH fails`() = runTest {
        val positionStore = FakePositionStore(localUpdatedAt = 1_000L)
        val api = FakeSessionApi(
            getResult = NetworkGetProgressResult.NetworkError(IOException("unused")),
            patchResult = NetworkSyncSessionResult.NetworkError(IOException("network down")),
        )
        val repo = buildRepo(api, positionStore)

        repo.setProgress("item-1", 1.0f)

        assertEquals(1, api.patchCallCount)
        assertNotNull(positionStore.updatedTimestamp)
        assertTrue(positionStore.updatedTimestamp!! > 0L)
    }
}
