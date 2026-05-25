package com.riffle.core.data

import com.riffle.core.database.ReadingPositionDao
import com.riffle.core.database.ReadingPositionEntity
import com.riffle.core.domain.EbookFormat
import com.riffle.core.domain.EpubOpenResult
import com.riffle.core.domain.LibraryItem
import com.riffle.core.domain.Server
import com.riffle.core.domain.ServerRepository
import com.riffle.core.domain.ServerUrl
import com.riffle.core.domain.TokenStorage
import com.riffle.core.network.AbsApiClient
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException

/**
 * Integration test: CFI persistence round-trip.
 *
 * Verifies that a freshly constructed repository instance picks up the reading position
 * saved by a previous instance — simulating what happens after an app restart.
 *
 * Uses a shared in-memory DAO (acting as the persistent Room database), a real
 * LocalStoreImpl, and a real MockWebServer.
 */
class EpubPositionIntegrationTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var server: MockWebServer
    private lateinit var cacheStore: LocalStoreImpl
    private lateinit var sharedPositionDao: InMemoryReadingPositionDao

    private val epubBytes = "PK fake epub content".toByteArray()

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        cacheStore = LocalStoreImpl(tmp.newFolder("cache"), ".epub")
        sharedPositionDao = InMemoryReadingPositionDao()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun buildRepo(): EpubRepositoryImpl = EpubRepositoryImpl(
        api = AbsApiClient(OkHttpClient()),
        cacheStore = cacheStore,
        downloadsStore = LocalStoreImpl(tmp.newFolder("downloads-${System.nanoTime()}"), ".epub"),
        positionStore = ReadingPositionStoreImpl(sharedPositionDao),
        serverRepository = object : ServerRepository {
            val activeServer = Server(
                id = "server-1",
                url = ServerUrl.parse(server.url("/").toString().trimEnd('/'))!!,
                displayName = "Test",
                isActive = true,
                insecureConnectionAllowed = false,
                username = "",
            )
            override fun observeAll(): Flow<List<Server>> = flowOf(listOf(activeServer))
            override suspend fun getActive(): Server = activeServer
            override suspend fun addServer(url: ServerUrl, username: String, password: String, insecureAllowed: Boolean) =
                throw UnsupportedOperationException()
            override suspend fun setActive(serverId: String) = Unit
            override suspend fun remove(serverId: String) = Unit
        },
        tokenStorage = object : TokenStorage {
            override suspend fun saveToken(serverId: String, token: String) = Unit
            override suspend fun getToken(serverId: String): String? = "test-token"
            override suspend fun deleteToken(serverId: String) = Unit
        },
    )

    private fun item() = LibraryItem(
        id = "item-1",
        libraryId = "lib-1",
        title = "Test Book",
        author = "Author",
        coverUrl = null,
        readingProgress = 0f,
        isCached = false,
        isDownloaded = false,
        ebookFormat = EbookFormat.Epub,
        ebookFileIno = "ino-42",
    )

    @Test
    fun `reading position survives repository restart`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody(Buffer().write(epubBytes)))
        val repo1 = buildRepo()

        // First session: open book then save position
        repo1.openEpub(item())
        repo1.saveReadingPosition("item-1", "epubcfi(/6/4[chap01]!/4/2[body01]/1:0)")

        // Simulate restart: new repository instance backed by the same persistent DAO
        val repo2 = buildRepo()
        val result = repo2.openEpub(item()) as EpubOpenResult.Success

        assertEquals("epubcfi(/6/4[chap01]!/4/2[body01]/1:0)", result.lastPosition)
        // Cache hit — no second network request
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `fresh book has no saved position after repository restart`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody(Buffer().write(epubBytes)))
        val repo1 = buildRepo()
        repo1.openEpub(item())
        // No saveReadingPosition call — simulates never having read the book

        val repo2 = buildRepo()
        val result = repo2.openEpub(item()) as EpubOpenResult.Success

        assertNull(result.lastPosition)
    }

    @Test
    fun `overwritten position is restored after repository restart`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody(Buffer().write(epubBytes)))
        val repo1 = buildRepo()
        repo1.openEpub(item())
        repo1.saveReadingPosition("item-1", "epubcfi(/6/2!/4/1:10)")
        repo1.saveReadingPosition("item-1", "epubcfi(/6/8!/4/1:99)")

        val repo2 = buildRepo()
        val result = repo2.openEpub(item()) as EpubOpenResult.Success

        assertEquals("epubcfi(/6/8!/4/1:99)", result.lastPosition)
    }

    private class InMemoryReadingPositionDao : ReadingPositionDao {
        private val entities = mutableMapOf<String, ReadingPositionEntity>()
        override suspend fun upsert(entity: ReadingPositionEntity) { entities[entity.itemId] = entity }
        override suspend fun getByItemId(itemId: String): ReadingPositionEntity? = entities[itemId]
        override suspend fun updateLocalTimestamp(itemId: String, millis: Long) {
            entities[itemId]?.let { entities[itemId] = it.copy(localUpdatedAt = millis) }
        }
    }
}
