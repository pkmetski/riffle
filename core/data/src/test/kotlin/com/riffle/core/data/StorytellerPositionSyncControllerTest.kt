package com.riffle.core.data

import com.riffle.core.network.NetworkResult
import com.riffle.core.network.StorytellerPosition

import com.riffle.core.domain.AuthenticateResult
import com.riffle.core.domain.CommitServerResult
import com.riffle.core.domain.PendingServer
import com.riffle.core.domain.ReadingPositionStore
import com.riffle.core.domain.Server
import com.riffle.core.domain.ServerRepository
import com.riffle.core.domain.ServerType
import com.riffle.core.domain.ServerUrl
import com.riffle.core.domain.TokenStorage
import com.riffle.core.network.StorytellerPositionApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class StorytellerPositionSyncControllerTest {

    private class FakePositionStore(var ts: Long) : ReadingPositionStore {
        var saved: String? = null
        var savedTs: Long? = null
        override suspend fun save(serverId: String, itemId: String, payload: String) { saved = payload }
        override suspend fun load(serverId: String, itemId: String): String? = saved
        override suspend fun loadLocalUpdatedAt(serverId: String, itemId: String): Long = ts
        override suspend fun updateLocalTimestamp(serverId: String, itemId: String, millis: Long) { savedTs = millis; ts = millis }
    }

    private class FakePositionApi(
        private val get: NetworkResult<StorytellerPosition?>,
    ) : StorytellerPositionApi {
        var putCount = 0
        override suspend fun getPosition(baseUrl: String, bookId: String, token: String, insecureAllowed: Boolean) = get
        override suspend fun putPosition(baseUrl: String, bookId: String, locatorJson: String, timestampMillis: Long, token: String, insecureAllowed: Boolean): NetworkResult<Unit> {
            putCount++; return NetworkResult.Success(Unit)
        }
    }

    private fun controller(api: StorytellerPositionApi, store: ReadingPositionStore) =
        StorytellerPositionSyncController(
            api = api,
            positionStore = store,
            serverRepository = object : ServerRepository {
                val server = Server("s1", ServerUrl.parse("http://localhost")!!, true, false, "")
                override fun observeAll(): Flow<List<Server>> = flowOf(listOf(server))
                override suspend fun getActive(): Server = server
                override suspend fun authenticate(url: ServerUrl, username: String, password: String, insecureAllowed: Boolean, serverType: ServerType) = throw UnsupportedOperationException()
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
        )

    @Test fun `remote newer pulls and saves locally`() = runTest {
        val store = FakePositionStore(ts = 1_000)
        val api = FakePositionApi(NetworkResult.Success(StorytellerPosition("""{"href":"x"}""", 2_000)))

        val outcome = controller(api, store).runCycle("item-1", localLocatorJson = """{"href":"old"}""")

        assertTrue(outcome is StorytellerSyncOutcome.PulledRemote)
        assertEquals("""{"href":"x"}""", store.saved)
        assertEquals(2_000L, store.savedTs)
        assertEquals(0, api.putCount)
    }

    @Test fun `local newer pushes to server`() = runTest {
        val store = FakePositionStore(ts = 5_000)
        val api = FakePositionApi(NetworkResult.Success(StorytellerPosition("""{"href":"x"}""", 1_000)))

        val outcome = controller(api, store).runCycle("item-1", localLocatorJson = """{"href":"local"}""")

        assertTrue(outcome is StorytellerSyncOutcome.PushedLocal)
        assertEquals(1, api.putCount)
    }

    @Test fun `GET failure is Offline with no push`() = runTest {
        val store = FakePositionStore(ts = 5_000)
        val api = FakePositionApi(NetworkResult.Offline(IOException("down")))

        val outcome = controller(api, store).runCycle("item-1", localLocatorJson = """{"href":"local"}""")

        assertTrue(outcome is StorytellerSyncOutcome.Offline)
        assertEquals(0, api.putCount)
    }
}
