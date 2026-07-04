package com.riffle.core.data

import com.riffle.core.domain.DefaultDispatcherProvider

import com.riffle.core.network.NetworkResult

import com.riffle.core.domain.EbookFormat
import com.riffle.core.domain.EpubDownloadResult
import com.riffle.core.domain.EpubOpenResult
import com.riffle.core.domain.EpubRepository
import com.riffle.core.domain.LibraryItem
import com.riffle.core.domain.ReadingPositionStore
import com.riffle.core.domain.Server
import com.riffle.core.domain.ServerRepository
import com.riffle.core.domain.ServerType
import com.riffle.core.domain.ServerUrl
import com.riffle.core.domain.TokenStorage
import com.riffle.core.network.AbsApiClient
import com.riffle.core.network.StorytellerBundleApi
import com.riffle.core.network.StorytellerBundleProbeApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
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
    private lateinit var cacheStore: LocalStoreImpl
    private lateinit var downloadsStore: LocalStoreImpl
    private lateinit var positionStore: FakePositionStore
    private lateinit var repo: EpubRepository

    private val epubBytes = "PK fake epub content".toByteArray()

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        cacheStore = LocalStoreImpl(tmp.newFolder("cache"), ".epub", com.riffle.core.domain.DefaultDispatcherProvider)
        downloadsStore = LocalStoreImpl(tmp.newFolder("downloads"), ".epub", com.riffle.core.domain.DefaultDispatcherProvider)
        positionStore = FakePositionStore()
        repo = EpubRepositoryImpl(
            api = AbsApiClient(OkHttpClient(), DefaultDispatcherProvider),
            cacheStore = cacheStore,
            downloadsStore = downloadsStore,
            positionStore = positionStore,
            serverRepository = fakeServerRepository(server.url("/").toString().trimEnd('/')),
            tokenStorage = fakeTokenStorage("test-token"),
        )
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun fakeServerRepository(baseUrl: String): ServerRepository =
        multiServerRepository(
            servers = listOf(
                Server(
                    id = "server-1",
                    url = ServerUrl.parse(baseUrl)!!,
                    isActive = true,
                    insecureConnectionAllowed = false,
                    username = "",
                    serverType = ServerType.AUDIOBOOKSHELF,
                ),
            ),
            activeId = "server-1",
        )

    private fun multiServerRepository(servers: List<Server>, activeId: String?): ServerRepository = object : ServerRepository {
        override fun observeAll(): Flow<List<Server>> = flowOf(servers)
        override suspend fun getActive(): Server? = servers.firstOrNull { it.id == activeId }
        override suspend fun getById(serverId: String): Server? = servers.firstOrNull { it.id == serverId }
        override suspend fun authenticate(url: ServerUrl, username: String, password: String, insecureAllowed: Boolean, serverType: com.riffle.core.domain.ServerType) =
            throw UnsupportedOperationException()
        override suspend fun commit(pending: com.riffle.core.domain.PendingServer, hiddenLibraryIds: Set<String>) =
            throw UnsupportedOperationException()
        override suspend fun setActive(serverId: String) = Unit
        override suspend fun remove(serverId: String) = Unit
        override suspend fun getServerVersion(serverId: String): String? = null
    }

    private fun fakeTokenStorage(token: String): TokenStorage = object : TokenStorage {
        override suspend fun saveToken(serverId: String, token: String) = Unit
        override suspend fun getToken(serverId: String): String = token
        override suspend fun deleteToken(serverId: String) = Unit
    }

    private class FakePositionStore : ReadingPositionStore {
        val store = mutableMapOf<Pair<String, String>, String>()
        override suspend fun save(serverId: String, itemId: String, payload: String) {
            store[serverId to itemId] = payload
        }
        override suspend fun load(serverId: String, itemId: String): String? = store[serverId to itemId]
        override suspend fun loadLocalUpdatedAt(serverId: String, itemId: String): Long = 0L
        override suspend fun updateLocalTimestamp(serverId: String, itemId: String, millis: Long) = Unit
    }

    private fun item(id: String = "item-1", ino: String? = "ino-42", serverId: String = "server-1") = LibraryItem(
        id = id,
        libraryId = "lib-1",
        title = "Test Book",
        author = "Author",
        coverUrl = null,
        readingProgress = 0f,
        isCached = false,
        isDownloaded = false,
        ebookFormat = EbookFormat.Epub,
        ebookFileIno = ino,
        serverId = serverId,
    )

    // --- openEpub ---

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
        assertTrue(cacheStore.get("server-1", "item-1") != null)
    }

    @Test
    fun `openEpub with cache hit makes no network request`() = runTest {
        cacheStore.save("server-1", "item-1", epubBytes.inputStream())
        val result = repo.openEpub(item())
        assertEquals(0, server.requestCount)
        assertTrue(result is EpubOpenResult.Success)
    }

    @Test
    fun `openEpub with downloads hit makes no network request`() = runTest {
        downloadsStore.save("server-1", "item-1", epubBytes.inputStream())
        val result = repo.openEpub(item())
        assertEquals(0, server.requestCount)
        assertTrue(result is EpubOpenResult.Success)
    }

    @Test
    fun `openEpub returns last saved reading position`() = runTest {
        cacheStore.save("server-1", "item-1", epubBytes.inputStream())
        positionStore.save("server-1", "item-1", "epubcfi(/6/4!/4/1:0)")
        val result = repo.openEpub(item()) as EpubOpenResult.Success
        assertEquals("epubcfi(/6/4!/4/1:0)", result.lastPosition)
    }

    @Test
    fun `openEpub returns null lastPosition for fresh book`() = runTest {
        cacheStore.save("server-1", "item-1", epubBytes.inputStream())
        val result = repo.openEpub(item()) as EpubOpenResult.Success
        assertNull(result.lastPosition)
    }

    @Test
    fun `openEpub with null ebookFileIno fetches ino from item detail endpoint then downloads`() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(200)
                .addHeader("Content-Type", "application/json")
                .setBody("""{"id":"item-1","libraryId":"lib-1","media":{"metadata":{"title":"T","authorName":"A"},"ebookFile":{"ino":"ino-42"}}}""")
        )
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

    // --- downloadEpub ---

    @Test
    fun `downloadEpub saves file to downloads store and returns Success`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody(Buffer().write(epubBytes)))
        val result = repo.downloadEpub(item())
        assertTrue(result is EpubDownloadResult.Success)
        assertTrue(downloadsStore.get("server-1", "item-1") != null)
    }

    @Test
    fun `downloadEpub returns AlreadyDownloaded when file already in downloads store`() = runTest {
        downloadsStore.save("server-1", "item-1", epubBytes.inputStream())
        val result = repo.downloadEpub(item())
        assertEquals(0, server.requestCount)
        assertTrue(result is EpubDownloadResult.AlreadyDownloaded)
    }

    @Test
    fun `downloadEpub does not write to cache store`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody(Buffer().write(epubBytes)))
        repo.downloadEpub(item())
        assertNull(cacheStore.get("server-1", "item-1"))
    }

    @Test
    fun `isDownloaded returns true after downloadEpub succeeds`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody(Buffer().write(epubBytes)))
        repo.downloadEpub(item())
        assertTrue(repo.isDownloaded("server-1", "item-1"))
    }

    @Test
    fun `isCached returns true after openEpub populates cache`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody(Buffer().write(epubBytes)))
        repo.openEpub(item())
        assertTrue(repo.isCached("server-1", "item-1"))
    }

    @Test
    fun `removeDownload deletes file from downloads store`() = runTest {
        downloadsStore.save("server-1", "item-1", epubBytes.inputStream())
        repo.removeDownload("server-1", "item-1")
        assertTrue(!repo.isDownloaded("server-1", "item-1"))
    }

    @Test
    fun `downloadEpub promotes cached file to downloads without network request`() = runTest {
        cacheStore.save("server-1", "item-1", epubBytes.inputStream())
        val result = repo.downloadEpub(item())
        assertEquals(0, server.requestCount)
        assertTrue(result is EpubDownloadResult.Success)
        assertTrue(downloadsStore.get("server-1", "item-1") != null)
        assertNull(cacheStore.get("server-1", "item-1"))
    }

    // ─── User-switch regression (item.serverId ≠ activeServer.id) ─────────────────────────────
    //
    // A user-switch on the same URL mints a fresh ServerEntity UUID (ServerRepositoryImpl.commit)
    // while leaving the previous row and its cached files under cacheDir/epubs/<oldId>/ in place.
    // The item stays keyed by its original serverId; the opener must too, or it looks in the wrong
    // directory, misses the truly-cached file, and falls into the network branch. That's the
    // "unable to resolve host" symptom on a cached book while offline.

    private fun multiTokenStorage(tokens: Map<String, String>): TokenStorage = object : TokenStorage {
        override suspend fun saveToken(serverId: String, token: String) = Unit
        override suspend fun getToken(serverId: String): String? = tokens[serverId]
        override suspend fun deleteToken(serverId: String) = Unit
    }

    private fun serverRow(id: String, baseUrl: String, isActive: Boolean) = Server(
        id = id,
        url = ServerUrl.parse(baseUrl)!!,
        isActive = isActive,
        insecureConnectionAllowed = false,
        username = "",
        serverType = ServerType.AUDIOBOOKSHELF,
    )

    private fun repoWith(serverRepository: ServerRepository, tokenStorage: TokenStorage): EpubRepository =
        EpubRepositoryImpl(
            api = AbsApiClient(OkHttpClient(), DefaultDispatcherProvider),
            cacheStore = cacheStore,
            downloadsStore = downloadsStore,
            positionStore = positionStore,
            serverRepository = serverRepository,
            tokenStorage = tokenStorage,
        )

    @Test
    fun `openEpub after user switch resolves cached file under item's serverId with zero network`() = runTest {
        val baseUrl = server.url("/").toString().trimEnd('/')
        val oldId = "old-user-server"
        val newId = "new-user-server"
        val repo = repoWith(
            multiServerRepository(
                servers = listOf(
                    serverRow(oldId, baseUrl, isActive = false),
                    serverRow(newId, baseUrl, isActive = true),
                ),
                activeId = newId,
            ),
            multiTokenStorage(mapOf(oldId to "old-token", newId to "new-token")),
        )
        cacheStore.save(oldId, "item-1", epubBytes.inputStream())

        val result = repo.openEpub(item(serverId = oldId))

        assertEquals("no network call should fire when cached file exists", 0, server.requestCount)
        assertTrue("Expected Success but got: $result", result is EpubOpenResult.Success)
    }

    @Test
    fun `openEpub after user switch resolves downloaded file under item's serverId with zero network`() = runTest {
        val baseUrl = server.url("/").toString().trimEnd('/')
        val oldId = "old-user-server"
        val newId = "new-user-server"
        val repo = repoWith(
            multiServerRepository(
                servers = listOf(
                    serverRow(oldId, baseUrl, isActive = false),
                    serverRow(newId, baseUrl, isActive = true),
                ),
                activeId = newId,
            ),
            multiTokenStorage(mapOf(oldId to "old-token", newId to "new-token")),
        )
        downloadsStore.save(oldId, "item-1", epubBytes.inputStream())

        val result = repo.openEpub(item(serverId = oldId))

        assertEquals(0, server.requestCount)
        assertTrue("Expected Success but got: $result", result is EpubOpenResult.Success)
    }

    @Test
    fun `openEpub cache miss hits the server matching item's serverId not the active one`() = runTest {
        val activeMock = MockWebServer().also { it.start() }
        try {
            val activeBase = activeMock.url("/").toString().trimEnd('/')
            val itemBase = server.url("/").toString().trimEnd('/')
            val activeId = "active-server"
            val itemId = "item-server"
            val repo = repoWith(
                multiServerRepository(
                    servers = listOf(
                        serverRow(itemId, itemBase, isActive = false),
                        serverRow(activeId, activeBase, isActive = true),
                    ),
                    activeId = activeId,
                ),
                multiTokenStorage(mapOf(itemId to "item-token", activeId to "active-token")),
            )
            server.enqueue(MockResponse().setResponseCode(200).setBody(Buffer().write(epubBytes)))

            val result = repo.openEpub(item(serverId = itemId))

            assertTrue("Expected Success but got: $result", result is EpubOpenResult.Success)
            assertEquals("the item's own server must receive the request", 1, server.requestCount)
            assertEquals("the active-but-unrelated server must be untouched", 0, activeMock.requestCount)
            val recorded = server.takeRequest()
            assertEquals("Bearer item-token", recorded.getHeader("Authorization"))
        } finally {
            activeMock.shutdown()
        }
    }

    @Test
    fun `openEpub returns NetworkError when item's serverId matches no server row`() = runTest {
        val baseUrl = server.url("/").toString().trimEnd('/')
        val repo = repoWith(
            multiServerRepository(
                servers = listOf(serverRow("some-other-server", baseUrl, isActive = true)),
                activeId = "some-other-server",
            ),
            multiTokenStorage(mapOf("some-other-server" to "some-token")),
        )

        val result = repo.openEpub(item(serverId = "orphaned-server"))

        assertTrue("Expected NetworkError but got: $result", result is EpubOpenResult.NetworkError)
        assertEquals("no network call should fire when the server row is missing", 0, server.requestCount)
    }

    @Test
    fun `openEpub after user switch writes newly-fetched file under item's serverId cache dir`() = runTest {
        val baseUrl = server.url("/").toString().trimEnd('/')
        val oldId = "old-user-server"
        val newId = "new-user-server"
        val repo = repoWith(
            multiServerRepository(
                servers = listOf(
                    serverRow(oldId, baseUrl, isActive = false),
                    serverRow(newId, baseUrl, isActive = true),
                ),
                activeId = newId,
            ),
            multiTokenStorage(mapOf(oldId to "old-token", newId to "new-token")),
        )
        server.enqueue(MockResponse().setResponseCode(200).setBody(Buffer().write(epubBytes)))

        repo.openEpub(item(serverId = oldId))

        assertNotNull("cache write must land under item.serverId", cacheStore.get(oldId, "item-1"))
        assertNull("cache write must NOT land under the active server id", cacheStore.get(newId, "item-1"))
    }

    @Test
    fun `downloadEpub after user switch promotes cached file under item's serverId with zero network`() = runTest {
        val baseUrl = server.url("/").toString().trimEnd('/')
        val oldId = "old-user-server"
        val newId = "new-user-server"
        val repo = repoWith(
            multiServerRepository(
                servers = listOf(
                    serverRow(oldId, baseUrl, isActive = false),
                    serverRow(newId, baseUrl, isActive = true),
                ),
                activeId = newId,
            ),
            multiTokenStorage(mapOf(oldId to "old-token", newId to "new-token")),
        )
        cacheStore.save(oldId, "item-1", epubBytes.inputStream())

        val result = repo.downloadEpub(item(serverId = oldId))

        assertEquals(0, server.requestCount)
        assertTrue("Expected Success but got: $result", result is EpubDownloadResult.Success)
        assertNotNull(downloadsStore.get(oldId, "item-1"))
        assertNull("original cache entry must be moved, not left behind", cacheStore.get(oldId, "item-1"))
    }

    @Test
    fun `downloadEpub cache miss hits the server matching item's serverId not the active one`() = runTest {
        val activeMock = MockWebServer().also { it.start() }
        try {
            val activeBase = activeMock.url("/").toString().trimEnd('/')
            val itemBase = server.url("/").toString().trimEnd('/')
            val activeId = "active-server"
            val itemId = "item-server"
            val repo = repoWith(
                multiServerRepository(
                    servers = listOf(
                        serverRow(itemId, itemBase, isActive = false),
                        serverRow(activeId, activeBase, isActive = true),
                    ),
                    activeId = activeId,
                ),
                multiTokenStorage(mapOf(itemId to "item-token", activeId to "active-token")),
            )
            server.enqueue(MockResponse().setResponseCode(200).setBody(Buffer().write(epubBytes)))

            val result = repo.downloadEpub(item(serverId = itemId))

            assertTrue("Expected Success but got: $result", result is EpubDownloadResult.Success)
            assertEquals(1, server.requestCount)
            assertEquals(0, activeMock.requestCount)
            val recorded = server.takeRequest()
            assertEquals("Bearer item-token", recorded.getHeader("Authorization"))
            assertNotNull(downloadsStore.get(itemId, "item-1"))
        } finally {
            activeMock.shutdown()
        }
    }

    @Test
    fun `downloadEpub returns NetworkError when item's serverId matches no server row and no cache`() = runTest {
        val baseUrl = server.url("/").toString().trimEnd('/')
        val repo = repoWith(
            multiServerRepository(
                servers = listOf(serverRow("some-other-server", baseUrl, isActive = true)),
                activeId = "some-other-server",
            ),
            multiTokenStorage(mapOf("some-other-server" to "some-token")),
        )

        val result = repo.downloadEpub(item(serverId = "orphaned-server"))

        assertTrue("Expected NetworkError but got: $result", result is EpubDownloadResult.NetworkError)
        assertEquals(0, server.requestCount)
        assertFalse(repo.isDownloaded("orphaned-server", "item-1"))
    }

}
