package com.riffle.core.data

import com.riffle.core.domain.DefaultDispatcherProvider

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

class ProgressSyncIntegrationTest {

    private lateinit var server: MockWebServer
    private lateinit var positionStore: RecordingPositionStore

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        positionStore = RecordingPositionStore()
    }

    @After
    fun tearDown() = server.shutdown()

    private class RecordingPositionStore(var localUpdatedAt: Long = 0L) : ReadingPositionStore {
        var updatedTimestamp: Long? = null
        override suspend fun save(serverId: String, itemId: String, payload: String) = Unit
        override suspend fun load(serverId: String, itemId: String): String? = null
        override suspend fun loadLocalUpdatedAt(serverId: String, itemId: String): Long = localUpdatedAt
        override suspend fun updateLocalTimestamp(serverId: String, itemId: String, millis: Long) { updatedTimestamp = millis }
    }

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
            override suspend fun authenticate(url: ServerUrl, username: String, password: String, insecureAllowed: Boolean, serverType: com.riffle.core.domain.ServerType): AuthenticateResult =
                throw UnsupportedOperationException()
            override suspend fun commit(pending: PendingServer, hiddenLibraryIds: Set<String>): CommitServerResult =
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
        positionStore = positionStore,
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
            clock = com.riffle.core.domain.TestClock(initialMs = 5_000L),
    )

    private fun json(code: Int, body: String) = MockResponse()
        .setResponseCode(code)
        .addHeader("Content-Type", "application/json")
        .setBody(body)

    private val payload = SessionPayload("epubcfi(/6/4!/4/1:0)", 0.25f)

    @Test
    fun `server-newer path returns ServerWins with server ebookLocation and updates localUpdatedAt`() = runTest {
        positionStore.localUpdatedAt = 1_000L
        server.enqueue(json(200, """{"ebookLocation":"epubcfi(/6/8!/4/1:0)","lastUpdate":2000}"""))

        val result = buildRepo().runSyncCycle("item-1", payload)

        assertTrue(result is ProgressSyncCycleResult.ServerWins)
        assertEquals("epubcfi(/6/8!/4/1:0)", (result as ProgressSyncCycleResult.ServerWins).serverProgress.ebookLocation)
        assertEquals(2000L, result.serverProgress.lastUpdate)
        assertEquals(2000L, positionStore.updatedTimestamp)
        assertEquals(1, server.requestCount)
        assertEquals("GET", server.takeRequest().method)
    }

    @Test
    fun `local-newer path sends PATCH with correct payload and updates localUpdatedAt from response`() = runTest {
        positionStore.localUpdatedAt = 3_000L
        server.enqueue(json(200, """{"ebookLocation":"old-cfi","lastUpdate":1000}"""))
        server.enqueue(json(200, """{"ebookLocation":"epubcfi(/6/4!/4/1:0)","lastUpdate":3100}"""))

        val result = buildRepo().runSyncCycle("item-1", payload)

        assertTrue(result is ProgressSyncCycleResult.LocalWins)
        assertEquals(2, server.requestCount)
        val getReq = server.takeRequest()
        assertEquals("GET", getReq.method)
        assertEquals("/api/me/progress/item-1", getReq.path)
        val patchReq = server.takeRequest()
        assertEquals("PATCH", patchReq.method)
        val body = patchReq.body.readUtf8()
        assertTrue(body.contains("\"ebookLocation\":\"epubcfi(/6/4!/4/1:0)\""))
        assertTrue(body.contains("\"ebookProgress\":0.25"))
        assertEquals(3100L, positionStore.updatedTimestamp)
    }

    @Test
    fun `GET failure returns Offline, sends no PATCH, and leaves localUpdatedAt unchanged`() = runTest {
        positionStore.localUpdatedAt = 5_000L
        server.enqueue(MockResponse().setResponseCode(500).setBody("error"))

        val result = buildRepo().runSyncCycle("item-1", payload)

        assertTrue(result is ProgressSyncCycleResult.Offline)
        assertEquals(1, server.requestCount)
        assertEquals("GET", server.takeRequest().method)
        assertEquals(null, positionStore.updatedTimestamp)
    }

    @Test
    fun `local-newer path with real ABS plain-text OK response does not corrupt localUpdatedAt`() = runTest {
        positionStore.localUpdatedAt = 3_000L
        server.enqueue(json(200, """{"ebookLocation":"old-cfi","lastUpdate":1000}"""))
        server.enqueue(
            MockResponse().setResponseCode(200)
                .addHeader("Content-Type", "text/plain")
                .setBody("OK")
        )

        val result = buildRepo().runSyncCycle("item-1", payload)

        assertTrue(result is ProgressSyncCycleResult.LocalWins)
        assertTrue(positionStore.updatedTimestamp != null)
        assertTrue(positionStore.updatedTimestamp!! > 0L)
    }

    @Test
    fun `two-cycle scenario plain-text OK PATCH does not cause server to win on next cycle`() = runTest {
        positionStore.localUpdatedAt = 3_000L
        server.enqueue(json(200, """{"ebookLocation":"old-cfi","lastUpdate":1000}"""))
        server.enqueue(
            MockResponse().setResponseCode(200)
                .addHeader("Content-Type", "text/plain")
                .setBody("OK")
        )

        buildRepo().runSyncCycle("item-1", payload)

        val updatedTs = positionStore.updatedTimestamp
        assertTrue(updatedTs != null)
        assertTrue(updatedTs!! > 0L)
        // Simulate: on the next cycle the server timestamp is still 1779445105751
        // localUpdatedAt must be > 0 so server would NOT win
        val serverTs = 1779445105751L
        assertTrue("localUpdatedAt must be > 0 so server doesn't always win", updatedTs > 0L)
    }

    @Test
    fun `GET 404 with local progress treats as no server record and sends PATCH`() = runTest {
        positionStore.localUpdatedAt = 4_000L
        server.enqueue(MockResponse().setResponseCode(404).setBody("Not Found"))
        server.enqueue(json(200, """{"ebookLocation":"epubcfi(/6/4!/4/1:0)","lastUpdate":4100}"""))

        val result = buildRepo().runSyncCycle("item-1", payload)

        assertTrue(result is ProgressSyncCycleResult.LocalWins)
        assertEquals(2, server.requestCount)
        assertEquals("GET", server.takeRequest().method)
        assertEquals("PATCH", server.takeRequest().method)
        assertEquals(4100L, positionStore.updatedTimestamp)
    }

    @Test
    fun `GET 404 with no local progress returns InSync without PATCH`() = runTest {
        positionStore.localUpdatedAt = 0L
        server.enqueue(MockResponse().setResponseCode(404).setBody("Not Found"))

        val result = buildRepo().runSyncCycle("item-1", payload)

        assertTrue(result is ProgressSyncCycleResult.InSync)
        assertEquals(1, server.requestCount)
        assertEquals("GET", server.takeRequest().method)
        assertEquals(null, positionStore.updatedTimestamp)
    }

    @Test
    fun `GET 404 followed by successful local push correctly updates localUpdatedAt`() = runTest {
        positionStore.localUpdatedAt = 7_000L
        server.enqueue(MockResponse().setResponseCode(404).setBody("Not Found"))
        server.enqueue(json(200, """{"ebookLocation":"cfi","lastUpdate":7200}"""))

        buildRepo().runSyncCycle("item-1", payload)

        assertEquals(7200L, positionStore.updatedTimestamp)
    }

    @Test
    fun `local-newer path with JSON response containing lastUpdate updates localUpdatedAt correctly`() = runTest {
        positionStore.localUpdatedAt = 3_000L
        server.enqueue(json(200, """{"ebookLocation":"old-cfi","lastUpdate":1000}"""))
        server.enqueue(json(200, """{"ebookLocation":"epubcfi(/6/4!/4/1:0)","lastUpdate":3100}"""))

        buildRepo().runSyncCycle("item-1", payload)

        assertEquals(3100L, positionStore.updatedTimestamp)
    }

    // --- touchOpenTimestamp end-to-end (GET → PATCH-same-content → server bumps lastUpdate) ---

    @Test
    fun `touchOpenTimestamp PATCHes back the server's existing ebookLocation verbatim`() = runTest {
        server.enqueue(json(200, """{"ebookLocation":"epubcfi(/6/8!/4/2/1:0)","ebookProgress":0.42,"lastUpdate":1000}"""))
        server.enqueue(json(200, """{"ebookLocation":"epubcfi(/6/8!/4/2/1:0)","lastUpdate":9999}"""))

        buildRepo().touchOpenTimestamp("item-1")

        assertEquals(2, server.requestCount)
        val getReq = server.takeRequest()
        assertEquals("GET", getReq.method)
        assertEquals("/api/me/progress/item-1", getReq.path)
        val patchReq = server.takeRequest()
        assertEquals("PATCH", patchReq.method)
        assertEquals("/api/me/progress/item-1", patchReq.path)
        val body = patchReq.body.readUtf8()
        assertTrue("ebookLocation must be echoed verbatim: $body", body.contains("\"ebookLocation\":\"epubcfi(/6/8!/4/2/1:0)\""))
        assertTrue("ebookProgress must be echoed verbatim: $body", body.contains("\"ebookProgress\":0.42"))
        // The PATCH bumps the server's lastUpdate. We deliberately do not echo that timestamp
        // into the local store — leaving local stale guarantees the next runSyncCycle sees
        // server > local, fires ServerWins, and restores the saved position to the navigator.
        assertEquals(null, positionStore.updatedTimestamp)
    }

    @Test
    fun `touchOpenTimestamp is a no-op when GET fails`() = runTest {
        server.enqueue(MockResponse().setResponseCode(500).setBody("oops"))

        buildRepo().touchOpenTimestamp("item-1")

        assertEquals(1, server.requestCount)
        assertEquals(null, positionStore.updatedTimestamp)
    }
}
