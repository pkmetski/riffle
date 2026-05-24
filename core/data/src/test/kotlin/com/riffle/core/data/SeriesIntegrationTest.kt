package com.riffle.core.data

import com.riffle.core.database.CollectionDao
import com.riffle.core.database.CollectionEntity
import com.riffle.core.database.CollectionItemEntity
import com.riffle.core.database.LibraryDao
import com.riffle.core.database.LibraryEntity
import com.riffle.core.database.LastOpenedAtRow
import com.riffle.core.database.ReadingProgressRow
import com.riffle.core.database.LibraryItemDao
import com.riffle.core.database.LibraryItemEntity
import com.riffle.core.database.SeriesDao
import com.riffle.core.database.SeriesEntity
import com.riffle.core.database.SeriesItemEntity
import com.riffle.core.domain.AddServerResult
import com.riffle.core.domain.LibraryRefreshResult
import com.riffle.core.domain.Server
import com.riffle.core.domain.ServerRepository
import com.riffle.core.domain.ServerUrl
import com.riffle.core.domain.TokenStorage
import com.riffle.core.network.AbsApiClient
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException

/**
 * Integration test: real ABS JSON → AbsApiClient → LibraryRepositoryImpl → FakeSeriesDao.
 * Used to reproduce and diagnose the api=0 series bug.
 */
class SeriesIntegrationTest {

    private lateinit var mockServer: MockWebServer

    private val fakeServerRepository = object : ServerRepository {
        lateinit var server: Server
        override fun observeAll() = MutableStateFlow(listOf(server))
        override suspend fun getActive() = server
        override suspend fun addServer(url: ServerUrl, username: String, password: String, insecureAllowed: Boolean): AddServerResult =
            AddServerResult.NetworkError(IOException())
        override suspend fun setActive(serverId: String) {}
        override suspend fun remove(serverId: String) {}
    }

    private val fakeTokenStorage = object : TokenStorage {
        override suspend fun saveToken(serverId: String, token: String) {}
        override suspend fun getToken(serverId: String) = "test-token"
        override suspend fun deleteToken(serverId: String) {}
    }

    @Before
    fun setUp() {
        mockServer = MockWebServer()
        mockServer.start()
        val serverUrl = mockServer.url("/").toString().trimEnd('/')
        fakeServerRepository.server = Server(
            id = "s1",
            url = ServerUrl.parse(serverUrl)!!,
            displayName = "test",
            isActive = true,
            insecureConnectionAllowed = false,
        )
    }

    @After
    fun tearDown() {
        mockServer.shutdown()
    }

    private class FakeSeriesDao : SeriesDao {
        val upsertedSeries = mutableListOf<SeriesEntity>()
        val upsertedItems = mutableListOf<SeriesItemEntity>()
        private val seriesData = mutableMapOf<String, MutableStateFlow<List<SeriesEntity>>>()
        private val itemData = mutableMapOf<String, MutableStateFlow<List<LibraryItemEntity>>>()
        override fun observeByLibraryId(libraryId: String): Flow<List<SeriesEntity>> =
            seriesData.getOrPut(libraryId) { MutableStateFlow(emptyList()) }
        override fun observeItemsBySeriesId(seriesId: String): Flow<List<LibraryItemEntity>> =
            itemData.getOrPut(seriesId) { MutableStateFlow(emptyList()) }
        override suspend fun upsertAll(series: List<SeriesEntity>) { upsertedSeries.addAll(series) }
        override suspend fun upsertAllItems(items: List<SeriesItemEntity>) { upsertedItems.addAll(items) }
        override suspend fun deleteByLibraryId(libraryId: String) {}
        override suspend fun deleteItemsByLibraryId(libraryId: String) {}
    }

    private class FakeLibraryDao : LibraryDao {
        override fun observeByServerId(serverId: String): Flow<List<LibraryEntity>> = MutableStateFlow(emptyList())
        override suspend fun upsertAll(libraries: List<LibraryEntity>) {}
        override suspend fun deleteByServerId(serverId: String) {}
        override suspend fun setUnsupported(libraryId: String, isUnsupported: Boolean) {}
    }

    private class FakeLibraryItemDao : LibraryItemDao {
        override fun observeByLibraryId(libraryId: String): Flow<List<LibraryItemEntity>> = MutableStateFlow(emptyList())
        override fun observeUngroupedByLibraryId(libraryId: String): Flow<List<LibraryItemEntity>> = MutableStateFlow(emptyList())
        override fun observeInProgress(libraryId: String): Flow<List<LibraryItemEntity>> = MutableStateFlow(emptyList())
        override fun observeFinished(libraryId: String): Flow<List<LibraryItemEntity>> = MutableStateFlow(emptyList())
        override fun observeAllBooks(libraryId: String): Flow<List<LibraryItemEntity>> = MutableStateFlow(emptyList())
        override suspend fun getById(itemId: String): LibraryItemEntity? = null
        override suspend fun upsertAll(items: List<LibraryItemEntity>) {}
        override suspend fun deleteByLibraryId(libraryId: String) {}
        override suspend fun updateLastOpenedAt(itemId: String, timestamp: Long) {}
        override suspend fun getLastOpenedAtMap(libraryId: String): List<LastOpenedAtRow> = emptyList()
        override suspend fun getReadingProgressMap(libraryId: String): List<ReadingProgressRow> = emptyList()
    }

    private class FakeCollectionDao : CollectionDao {
        override fun observeByLibraryId(libraryId: String): Flow<List<CollectionEntity>> = MutableStateFlow(emptyList())
        override fun observeItemsByCollectionId(collectionId: String): Flow<List<LibraryItemEntity>> = MutableStateFlow(emptyList())
        override suspend fun upsertAll(collections: List<CollectionEntity>) {}
        override suspend fun upsertAllItems(items: List<CollectionItemEntity>) {}
        override suspend fun deleteByLibraryId(libraryId: String) {}
        override suspend fun deleteItemsByLibraryId(libraryId: String) {}
    }

    private fun makeRepo(seriesDao: FakeSeriesDao = FakeSeriesDao()) = LibraryRepositoryImpl(
        api = AbsApiClient(OkHttpClient()),
        libraryDao = FakeLibraryDao(),
        libraryItemDao = FakeLibraryItemDao(),
        seriesDao = seriesDao,
        collectionDao = FakeCollectionDao(),
        serverRepository = fakeServerRepository,
        tokenStorage = fakeTokenStorage,
    )

    // JSON matching a real Audiobookshelf /api/libraries/{id}/series?minified=1 response
    private val oneSeriesJson = """
        {
          "results": [
            {
              "id": "6824c42f-856b-409c-b32e-ad4a472f7447",
              "name": "Discworld",
              "libraryId": "e77c113d-4383-488d-956f-89c18db431ac",
              "books": [
                {
                  "id": "3567e8b4-bed8-442f-9733-5dd642295462",
                  "ino": "1511850",
                  "libraryId": "e77c113d-4383-488d-956f-89c18db431ac",
                  "seriesSequence": "1",
                  "mediaType": "book",
                  "media": {
                    "metadata": {
                      "title": "The Colour Of Magic",
                      "authorName": "Terry Pratchett"
                    },
                    "ebookFormat": "epub"
                  }
                },
                {
                  "id": "7a1bc2d3-ef45-6789-ab01-23cd45ef6789",
                  "ino": "1511851",
                  "libraryId": "e77c113d-4383-488d-956f-89c18db431ac",
                  "seriesSequence": "2",
                  "mediaType": "book",
                  "media": {
                    "metadata": {
                      "title": "The Light Fantastic",
                      "authorName": "Terry Pratchett"
                    },
                    "ebookFormat": "epub"
                  }
                }
              ]
            }
          ],
          "total": 26,
          "limit": 10,
          "page": 0,
          "sortDesc": false,
          "minified": true,
          "include": ""
        }
    """.trimIndent()

    @Test
    fun `refreshSeries with real ABS response shape stores series in DAO`() = runTest {
        mockServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(oneSeriesJson)
                .addHeader("Content-Type", "application/json")
        )
        val seriesDao = FakeSeriesDao()

        val result = makeRepo(seriesDao).refreshSeries("e77c113d-4383-488d-956f-89c18db431ac")

        assertTrue("Expected Success but was: $result", result is LibraryRefreshResult.Success)
        assertEquals("1 series should be upserted to DAO", 1, seriesDao.upsertedSeries.size)
        assertEquals("Discworld", seriesDao.upsertedSeries[0].name)
        assertEquals(2, seriesDao.upsertedSeries[0].bookCount)
        assertEquals("2 series items (books) should be upserted", 2, seriesDao.upsertedItems.size)
    }

    @Test
    fun `refreshSeries sends correct request path and auth header`() = runTest {
        mockServer.enqueue(
            MockResponse().setResponseCode(200).setBody("""{"results":[]}""")
                .addHeader("Content-Type", "application/json")
        )

        makeRepo().refreshSeries("e77c113d-4383-488d-956f-89c18db431ac")

        val request = mockServer.takeRequest()
        assertEquals("Bearer test-token", request.getHeader("Authorization"))
        assertEquals(
            "/api/libraries/e77c113d-4383-488d-956f-89c18db431ac/series?limit=500",
            request.path
        )
    }

    @Test
    fun `refreshSeries when server returns 401 returns NetworkError`() = runTest {
        mockServer.enqueue(MockResponse().setResponseCode(401).setBody("""{"error":"Unauthorized"}"""))

        val result = makeRepo().refreshSeries("e77c113d-4383-488d-956f-89c18db431ac")

        assertTrue("Expected NetworkError but was: $result", result is LibraryRefreshResult.NetworkError)
    }
}
