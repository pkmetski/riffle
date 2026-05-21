package com.riffle.core.data

import com.riffle.core.domain.EpubOpenResult
import com.riffle.core.domain.LibraryItem
import com.riffle.core.domain.ReadingPositionStore
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class EpubRepositoryTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var server: MockWebServer
    private lateinit var cacheManager: EpubCacheManagerImpl
    private lateinit var positionStore: FakePositionStore
    private lateinit var repo: EpubRepositoryImpl

    private val epubBytes = "PK fake epub content".toByteArray()

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        cacheManager = EpubCacheManagerImpl(tmp.newFolder("cache"))
        positionStore = FakePositionStore()
        repo = EpubRepositoryImpl(
            api = AbsApiClient(OkHttpClient()),
            cacheManager = cacheManager,
            positionStore = positionStore,
            serverRepository = fakeServerRepository(server.url("/").toString().trimEnd('/')),
            tokenStorage = fakeTokenStorage("test-token"),
        )
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun fakeServerRepository(baseUrl: String): ServerRepository = object : ServerRepository {
        val activeServer = Server(
            id = "server-1",
            url = ServerUrl.parse(baseUrl)!!,
            displayName = "Test",
            isActive = true,
            insecureConnectionAllowed = false,
        )
        override fun observeAll(): Flow<List<Server>> = flowOf(listOf(activeServer))
        override suspend fun getActive(): Server = activeServer
        override suspend fun addServer(url: ServerUrl, username: String, password: String, insecureAllowed: Boolean) =
            throw UnsupportedOperationException()
        override suspend fun setActive(serverId: String) = Unit
        override suspend fun remove(serverId: String) = Unit
    }

    private fun fakeTokenStorage(token: String): TokenStorage = object : TokenStorage {
        override suspend fun saveToken(serverId: String, t: String) = Unit
        override suspend fun getToken(serverId: String): String = token
        override suspend fun deleteToken(serverId: String) = Unit
    }

    private class FakePositionStore : ReadingPositionStore {
        val store = mutableMapOf<String, String>()
        override suspend fun save(itemId: String, cfi: String) { store[itemId] = cfi }
        override suspend fun load(itemId: String): String? = store[itemId]
    }

    private fun item(id: String = "item-1", ino: String? = "ino-42") = LibraryItem(
        id = id,
        libraryId = "lib-1",
        title = "Test Book",
        author = "Author",
        coverUrl = null,
        readingProgress = 0f,
        isCached = false,
        isDownloaded = false,
        isSupported = true,
        ebookFileIno = ino,
    )

    @Test
    fun `openEpub with cache miss downloads file from server and returns Success`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody(Buffer().write(epubBytes)))
        val result = repo.openEpub(item())
        assertTrue(result is EpubOpenResult.Success)
    }

    @Test
    fun `openEpub with cache miss writes file to cache for subsequent opens`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody(Buffer().write(epubBytes)))
        repo.openEpub(item())
        assertTrue(cacheManager.getCachedEpub("item-1") != null)
    }

    @Test
    fun `openEpub with cache hit makes no network request`() = runTest {
        cacheManager.cacheEpub("item-1", epubBytes)
        val result = repo.openEpub(item())
        assertEquals(0, server.requestCount)
        assertTrue(result is EpubOpenResult.Success)
    }

    @Test
    fun `openEpub returns last saved reading position`() = runTest {
        cacheManager.cacheEpub("item-1", epubBytes)
        positionStore.save("item-1", "epubcfi(/6/4!/4/1:0)")
        val result = repo.openEpub(item()) as EpubOpenResult.Success
        assertEquals("epubcfi(/6/4!/4/1:0)", result.lastPosition)
    }

    @Test
    fun `openEpub returns null lastPosition for fresh book`() = runTest {
        cacheManager.cacheEpub("item-1", epubBytes)
        val result = repo.openEpub(item()) as EpubOpenResult.Success
        assertNull(result.lastPosition)
    }

    // The list endpoint (/api/libraries/{id}/items) never includes ebookFile, so ebookFileIno
    // is always null after a refresh. EpubRepositoryImpl must fall back to GET /api/items/{id}
    // to retrieve the ino before downloading.
    @Test
    fun `openEpub with null ebookFileIno fetches ino from item detail endpoint then downloads`() = runTest {
        // First request: GET /api/items/item-1 — returns the ino
        server.enqueue(
            MockResponse().setResponseCode(200)
                .addHeader("Content-Type", "application/json")
                .setBody("""{"id":"item-1","libraryId":"lib-1","media":{"metadata":{"title":"T","authorName":"A"},"ebookFile":{"ino":"ino-42"}}}""")
        )
        // Second request: GET /api/items/item-1/ebook/ino-42 — returns the epub bytes
        server.enqueue(MockResponse().setResponseCode(200).setBody(Buffer().write(epubBytes)))

        val result = repo.openEpub(item(ino = null))

        assertTrue("Expected Success but got: $result", result is EpubOpenResult.Success)
        assertEquals(2, server.requestCount)
        val inoRequest = server.takeRequest()
        assertTrue(inoRequest.path!!.contains("/api/items/item-1"))
        val downloadRequest = server.takeRequest()
        assertTrue(downloadRequest.path!!.contains("/api/items/item-1/ebook/ino-42"))
    }

    @Test
    fun `openEpub with null ebookFileIno returns NetworkError when item detail fetch fails`() = runTest {
        server.enqueue(MockResponse().setResponseCode(404).setBody("{}"))

        val result = repo.openEpub(item(ino = null))

        assertTrue("Expected NetworkError but got: $result", result is EpubOpenResult.NetworkError)
    }
}
