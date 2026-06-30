package com.riffle.core.data

import com.riffle.core.domain.DefaultDispatcherProvider

import com.riffle.core.domain.ReadingPositionStore
import com.riffle.core.domain.Server
import com.riffle.core.domain.ServerRepository
import com.riffle.core.domain.ServerUrl
import com.riffle.core.domain.SessionPayload
import com.riffle.core.domain.SyncSessionResult
import com.riffle.core.domain.TokenStorage
import com.riffle.core.network.AbsApiClient
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ReadingSessionIntegrationTest {

    private lateinit var server: MockWebServer
    private lateinit var repo: ReadingSessionRepositoryImpl

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        repo = buildRepo()
    }

    @After
    fun tearDown() = server.shutdown()

    private fun buildRepo() = ReadingSessionRepositoryImpl(
        api = AbsApiClient(OkHttpClient(), DefaultDispatcherProvider),
        serverRepository = object : ServerRepository {
            val activeServer = Server(
                id = "server-1",
                url = ServerUrl.parse(server.url("/").toString().trimEnd('/'))!!,
                isActive = true,
                insecureConnectionAllowed = false,
                username = "",
            )
            override fun observeAll(): Flow<List<Server>> = flowOf(listOf(activeServer))
            override suspend fun getActive(): Server = activeServer
            override suspend fun authenticate(url: ServerUrl, username: String, password: String, insecureAllowed: Boolean, serverType: com.riffle.core.domain.ServerType) =
                throw UnsupportedOperationException()
            override suspend fun commit(pending: com.riffle.core.domain.PendingServer, hiddenLibraryIds: Set<String>) =
                throw UnsupportedOperationException()
            override suspend fun setActive(serverId: String) = Unit
            override suspend fun remove(serverId: String) = Unit
            override suspend fun getServerVersion(serverId: String): String? = null
        },
        tokenStorage = object : TokenStorage {
            override suspend fun saveToken(serverId: String, token: String) = Unit
            override suspend fun getToken(serverId: String): String? = "test-token"
            override suspend fun deleteToken(serverId: String) = Unit
        },
        positionStore = object : ReadingPositionStore {
            override suspend fun save(serverId: String, itemId: String, payload: String) = Unit
            override suspend fun load(serverId: String, itemId: String): String? = null
            override suspend fun loadLocalUpdatedAt(serverId: String, itemId: String): Long = 0L
            override suspend fun updateLocalTimestamp(serverId: String, itemId: String, millis: Long) = Unit
        },
        audiobookPositionStore = object : com.riffle.core.domain.AudiobookPositionStore {
            override suspend fun save(serverId: String, itemId: String, payload: Double) = Unit
            override suspend fun load(serverId: String, itemId: String): Double? = null
            override suspend fun loadLocalUpdatedAt(serverId: String, itemId: String): Long = 0L
            override suspend fun updateLocalTimestamp(serverId: String, itemId: String, millis: Long) = Unit
        },
        readaloudResumeStore = object : com.riffle.core.domain.ReadaloudResumeStore {
            override suspend fun save(serverId: String, itemId: String, position: com.riffle.core.domain.ReadaloudResumePosition) = Unit
            override suspend fun load(serverId: String, itemId: String): com.riffle.core.domain.ReadaloudResumePosition? = null
            override suspend fun clear(serverId: String, itemId: String) = Unit
        },
        libraryItemDao = FakeLibraryItemDao(),
    )

    @Test
    fun `syncProgress sends PATCH to correct path with payload`() = runTest {
        server.enqueue(json(200, "{}"))

        val payload = SessionPayload("epubcfi(/6/4!/4/1:0)", 0.25f)
        val result = repo.syncProgress("item-1", payload)

        assertTrue(result is SyncSessionResult.Success)
        val req = server.takeRequest()
        assertEquals("PATCH", req.method)
        assertEquals("/api/me/progress/item-1", req.path)
        val body = req.body.readUtf8()
        assertTrue(body.contains("\"ebookLocation\":\"epubcfi(/6/4!/4/1:0)\""))
        assertTrue(body.contains("\"ebookProgress\":0.25"))
    }

    @Test
    fun `syncProgress returns NetworkError on server failure`() = runTest {
        server.enqueue(json(500, "{}"))
        val result = repo.syncProgress("item-1", SessionPayload("cfi", 0.5f))
        assertTrue(result is SyncSessionResult.NetworkError)
    }

    @Test
    fun `syncProgress returns NetworkError when server is unreachable`() = runTest {
        server.shutdown()
        val result = buildRepo().syncProgress("item-x", SessionPayload("cfi", 0f))
        assertTrue(result is SyncSessionResult.NetworkError)
    }

    private fun json(code: Int, body: String) = MockResponse()
        .setResponseCode(code)
        .addHeader("Content-Type", "application/json")
        .setBody(body)
}
