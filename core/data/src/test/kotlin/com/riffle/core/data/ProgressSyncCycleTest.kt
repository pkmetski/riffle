package com.riffle.core.data

import com.riffle.core.domain.AddServerResult
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
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class ProgressSyncCycleTest {

    // --- fakes ---

    private class FakePositionStore(var localUpdatedAt: Long = 0L) : ReadingPositionStore {
        var updatedTimestamp: Long? = null
        override suspend fun save(itemId: String, cfi: String) = Unit
        override suspend fun load(itemId: String): String? = null
        override suspend fun loadLocalUpdatedAt(itemId: String): Long = localUpdatedAt
        override suspend fun updateLocalTimestamp(itemId: String, millis: Long) { updatedTimestamp = millis }
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
                val server = Server("s1", ServerUrl.parse("http://localhost")!!, "T", true, false)
                override fun observeAll(): Flow<List<Server>> = flowOf(listOf(server))
                override suspend fun getActive(): Server = server
                override suspend fun addServer(url: ServerUrl, username: String, password: String, insecureAllowed: Boolean): AddServerResult = throw UnsupportedOperationException()
                override suspend fun setActive(serverId: String) = Unit
                override suspend fun remove(serverId: String) = Unit
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
                NetworkServerProgress("epubcfi(/6/8!/4/1:0)", serverTs)
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
            getResult = NetworkGetProgressResult.Success(NetworkServerProgress("old-cfi", serverTs)),
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
            getResult = NetworkGetProgressResult.Success(NetworkServerProgress("cfi", ts))
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
}
