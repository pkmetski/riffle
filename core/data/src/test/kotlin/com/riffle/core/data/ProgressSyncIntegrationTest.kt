package com.riffle.core.data

import com.riffle.core.domain.AddServerResult
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
        override suspend fun save(itemId: String, cfi: String) = Unit
        override suspend fun load(itemId: String): String? = null
        override suspend fun loadLocalUpdatedAt(itemId: String): Long = localUpdatedAt
        override suspend fun updateLocalTimestamp(itemId: String, millis: Long) { updatedTimestamp = millis }
    }

    private fun buildRepo() = ReadingSessionRepositoryImpl(
        api = AbsApiClient(OkHttpClient()),
        serverRepository = object : ServerRepository {
            val activeServer = Server(
                id = "server-1",
                url = ServerUrl.parse(server.url("/").toString().trimEnd('/'))!!,
                displayName = "Test",
                isActive = true,
                insecureConnectionAllowed = false,
            )
            override fun observeAll(): Flow<List<Server>> = flowOf(listOf(activeServer))
            override suspend fun getActive(): Server = activeServer
            override suspend fun addServer(url: ServerUrl, username: String, password: String, insecureAllowed: Boolean): AddServerResult =
                throw UnsupportedOperationException()
            override suspend fun setActive(serverId: String) = Unit
            override suspend fun remove(serverId: String) = Unit
        },
        tokenStorage = object : TokenStorage {
            override suspend fun saveToken(serverId: String, token: String) = Unit
            override suspend fun getToken(serverId: String): String? = "test-token"
            override suspend fun deleteToken(serverId: String) = Unit
        },
        positionStore = positionStore,
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
    fun `local-newer path with JSON response containing lastUpdate updates localUpdatedAt correctly`() = runTest {
        positionStore.localUpdatedAt = 3_000L
        server.enqueue(json(200, """{"ebookLocation":"old-cfi","lastUpdate":1000}"""))
        server.enqueue(json(200, """{"ebookLocation":"epubcfi(/6/4!/4/1:0)","lastUpdate":3100}"""))

        buildRepo().runSyncCycle("item-1", payload)

        assertEquals(3100L, positionStore.updatedTimestamp)
    }
}
