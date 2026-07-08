package com.riffle.core.data

import com.riffle.core.domain.DefaultDispatcherProvider

import com.riffle.core.domain.ReadingPositionStore
import com.riffle.core.domain.Source
import com.riffle.core.domain.SourceRepository
import com.riffle.core.domain.SourceUrl
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

    private lateinit var source: MockWebServer
    private lateinit var repo: ReadingSessionRepositoryImpl

    @Before
    fun setUp() {
        source = MockWebServer()
        source.start()
        repo = buildRepo()
    }

    @After
    fun tearDown() = source.shutdown()

    private fun buildRepo(): ReadingSessionRepositoryImpl {
        val sourceRepo = object : SourceRepository {
            val activeServer = Source(
                id = "source-1",
                url = SourceUrl.parse(source.url("/").toString().trimEnd('/'))!!,
                isActive = true,
                insecureConnectionAllowed = false,
                username = "",
            )
            override fun observeAll(): Flow<List<Source>> = flowOf(listOf(activeServer))
            override suspend fun getActive(): Source = activeServer
            override suspend fun authenticate(url: SourceUrl, username: String, password: String, insecureAllowed: Boolean, serverType: com.riffle.core.domain.ServerType) =
                throw UnsupportedOperationException()
            override suspend fun commit(pending: com.riffle.core.domain.PendingSource, hiddenLibraryIds: Set<String>) =
                throw UnsupportedOperationException()
            override suspend fun setActive(sourceId: String) = Unit
            override suspend fun remove(sourceId: String) = Unit
            override suspend fun getSourceVersion(sourceId: String): String? = null
        }
        return ReadingSessionRepositoryImpl(
            catalogRegistry = TestCatalogRegistry(sourceRepo, mapOf("source-1" to "test-token")),
            sourceRepository = sourceRepo,
            positionStore = object : ReadingPositionStore {
            override suspend fun save(sourceId: String, itemId: String, payload: String) = Unit
            override suspend fun load(sourceId: String, itemId: String): String? = null
            override suspend fun loadLocalUpdatedAt(sourceId: String, itemId: String): Long = 0L
            override suspend fun updateLocalTimestamp(sourceId: String, itemId: String, millis: Long) = Unit
        },
        audiobookPositionStore = object : com.riffle.core.domain.AudiobookPositionStore {
            override suspend fun save(sourceId: String, itemId: String, payload: Double) = Unit
            override suspend fun load(sourceId: String, itemId: String): Double? = null
            override suspend fun loadLocalUpdatedAt(sourceId: String, itemId: String): Long = 0L
            override suspend fun updateLocalTimestamp(sourceId: String, itemId: String, millis: Long) = Unit
        },
        readaloudResumeStore = object : com.riffle.core.domain.ReadaloudResumeStore {
            override suspend fun save(sourceId: String, itemId: String, position: com.riffle.core.domain.ReadaloudResumePosition) = Unit
            override suspend fun load(sourceId: String, itemId: String): com.riffle.core.domain.ReadaloudResumePosition? = null
            override suspend fun clear(sourceId: String, itemId: String) = Unit
        },
        libraryItemDao = FakeLibraryItemDao(),
            clock = com.riffle.core.domain.TestClock(),
        )
    }

    @Test
    fun `syncProgress sends PATCH to correct path with payload`() = runTest {
        source.enqueue(json(200, "{}"))

        val payload = SessionPayload("epubcfi(/6/4!/4/1:0)", 0.25f)
        val result = repo.syncProgress("item-1", payload)

        assertTrue(result is SyncSessionResult.Success)
        val req = source.takeRequest()
        assertEquals("PATCH", req.method)
        assertEquals("/api/me/progress/item-1", req.path)
        val body = req.body.readUtf8()
        assertTrue(body.contains("\"ebookLocation\":\"epubcfi(/6/4!/4/1:0)\""))
        assertTrue(body.contains("\"ebookProgress\":0.25"))
    }

    @Test
    fun `syncProgress returns NetworkError on source failure`() = runTest {
        source.enqueue(json(500, "{}"))
        val result = repo.syncProgress("item-1", SessionPayload("cfi", 0.5f))
        assertTrue(result is SyncSessionResult.NetworkError)
    }

    @Test
    fun `syncProgress returns NetworkError when source is unreachable`() = runTest {
        source.shutdown()
        val result = buildRepo().syncProgress("item-x", SessionPayload("cfi", 0f))
        assertTrue(result is SyncSessionResult.NetworkError)
    }

    private fun json(code: Int, body: String) = MockResponse()
        .setResponseCode(code)
        .addHeader("Content-Type", "application/json")
        .setBody(body)
}
