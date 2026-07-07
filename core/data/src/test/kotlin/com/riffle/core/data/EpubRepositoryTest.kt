package com.riffle.core.data

import com.riffle.core.domain.DefaultDispatcherProvider

import com.riffle.core.network.NetworkResult

import com.riffle.core.domain.EbookFormat
import com.riffle.core.domain.EpubDownloadResult
import com.riffle.core.domain.EpubOpenResult
import com.riffle.core.domain.EpubRepository
import com.riffle.core.domain.LibraryItem
import com.riffle.core.domain.ReadingPositionStore
import com.riffle.core.domain.Source
import com.riffle.core.domain.SourceRepository
import com.riffle.core.domain.ServerType
import com.riffle.core.domain.SourceUrl
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

    private lateinit var source: MockWebServer
    private lateinit var cacheStore: LocalStoreImpl
    private lateinit var downloadsStore: LocalStoreImpl
    private lateinit var positionStore: FakePositionStore
    private lateinit var repo: EpubRepository

    private val epubBytes = "PK fake epub content".toByteArray()

    @Before
    fun setUp() {
        source = MockWebServer()
        source.start()
        cacheStore = LocalStoreImpl(tmp.newFolder("cache"), ".epub", com.riffle.core.domain.DefaultDispatcherProvider)
        downloadsStore = LocalStoreImpl(tmp.newFolder("downloads"), ".epub", com.riffle.core.domain.DefaultDispatcherProvider)
        positionStore = FakePositionStore()
        repo = EpubRepositoryImpl(
            api = AbsApiClient(OkHttpClient(), DefaultDispatcherProvider),
            cacheStore = cacheStore,
            downloadsStore = downloadsStore,
            positionStore = positionStore,
            sourceRepository = fakeServerRepository(source.url("/").toString().trimEnd('/')),
            tokenStorage = fakeTokenStorage("test-token"),
        )
    }

    @After
    fun tearDown() {
        source.shutdown()
    }

    private fun fakeServerRepository(baseUrl: String): SourceRepository =
        multiServerRepository(
            servers = listOf(
                Source(
                    id = "source-1",
                    url = SourceUrl.parse(baseUrl)!!,
                    isActive = true,
                    insecureConnectionAllowed = false,
                    username = "",
                    serverType = ServerType.AUDIOBOOKSHELF,
                ),
            ),
            activeId = "source-1",
        )

    private fun multiServerRepository(servers: List<Source>, activeId: String?): SourceRepository = object : SourceRepository {
        override fun observeAll(): Flow<List<Source>> = flowOf(servers)
        override suspend fun getActive(): Source? = servers.firstOrNull { it.id == activeId }
        override suspend fun getById(sourceId: String): Source? = servers.firstOrNull { it.id == sourceId }
        override suspend fun authenticate(url: SourceUrl, username: String, password: String, insecureAllowed: Boolean, serverType: com.riffle.core.domain.ServerType) =
            throw UnsupportedOperationException()
        override suspend fun commit(pending: com.riffle.core.domain.PendingSource, hiddenLibraryIds: Set<String>) =
            throw UnsupportedOperationException()
        override suspend fun setActive(sourceId: String) = Unit
        override suspend fun remove(sourceId: String) = Unit
        override suspend fun getSourceVersion(sourceId: String): String? = null
    }

    private fun fakeTokenStorage(token: String): TokenStorage = object : TokenStorage {
        override suspend fun saveToken(sourceId: String, token: String) = Unit
        override suspend fun getToken(sourceId: String): String = token
        override suspend fun deleteToken(sourceId: String) = Unit
    }

    private class FakePositionStore : ReadingPositionStore {
        val store = mutableMapOf<Pair<String, String>, String>()
        override suspend fun save(sourceId: String, itemId: String, payload: String) {
            store[sourceId to itemId] = payload
        }
        override suspend fun load(sourceId: String, itemId: String): String? = store[sourceId to itemId]
        override suspend fun loadLocalUpdatedAt(sourceId: String, itemId: String): Long = 0L
        override suspend fun updateLocalTimestamp(sourceId: String, itemId: String, millis: Long) = Unit
    }

    private fun item(id: String = "item-1", ino: String? = "ino-42", sourceId: String = "source-1") = LibraryItem(
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
        sourceId = sourceId,
    )

    // --- openEpub ---

    @Test
    fun `openEpub with cache miss downloads file from source and returns Success`() = runTest {
        source.enqueue(MockResponse().setResponseCode(200).setBody(Buffer().write(epubBytes)))
        val result = repo.openEpub(item())
        assertTrue(result is EpubOpenResult.Success)
    }

    @Test
    fun `openEpub with cache miss writes file to cache for subsequent opens`() = runTest {
        source.enqueue(MockResponse().setResponseCode(200).setBody(Buffer().write(epubBytes)))
        repo.openEpub(item())
        assertTrue(cacheStore.get("source-1", "item-1") != null)
    }

    @Test
    fun `openEpub with cache hit makes no network request`() = runTest {
        cacheStore.save("source-1", "item-1", epubBytes.inputStream())
        val result = repo.openEpub(item())
        assertEquals(0, source.requestCount)
        assertTrue(result is EpubOpenResult.Success)
    }

    @Test
    fun `openEpub with downloads hit makes no network request`() = runTest {
        downloadsStore.save("source-1", "item-1", epubBytes.inputStream())
        val result = repo.openEpub(item())
        assertEquals(0, source.requestCount)
        assertTrue(result is EpubOpenResult.Success)
    }

    @Test
    fun `openEpub returns last saved reading position`() = runTest {
        cacheStore.save("source-1", "item-1", epubBytes.inputStream())
        positionStore.save("source-1", "item-1", "epubcfi(/6/4!/4/1:0)")
        val result = repo.openEpub(item()) as EpubOpenResult.Success
        assertEquals("epubcfi(/6/4!/4/1:0)", result.lastPosition)
    }

    @Test
    fun `openEpub returns null lastPosition for fresh book`() = runTest {
        cacheStore.save("source-1", "item-1", epubBytes.inputStream())
        val result = repo.openEpub(item()) as EpubOpenResult.Success
        assertNull(result.lastPosition)
    }

    @Test
    fun `openEpub with null ebookFileIno fetches ino from item detail endpoint then downloads`() = runTest {
        source.enqueue(
            MockResponse().setResponseCode(200)
                .addHeader("Content-Type", "application/json")
                .setBody("""{"id":"item-1","libraryId":"lib-1","media":{"metadata":{"title":"T","authorName":"A"},"ebookFile":{"ino":"ino-42"}}}""")
        )
        source.enqueue(MockResponse().setResponseCode(200).setBody(Buffer().write(epubBytes)))

        val result = repo.openEpub(item(ino = null))

        assertTrue("Expected Success but got: $result", result is EpubOpenResult.Success)
        assertEquals(2, source.requestCount)
        val inoRequest = source.takeRequest()
        assertTrue(inoRequest.path!!.contains("/api/items/item-1"))
        val downloadRequest = source.takeRequest()
        assertTrue(downloadRequest.path!!.contains("/api/items/item-1/ebook/ino-42"))
    }

    @Test
    fun `openEpub with null ebookFileIno returns NetworkError when item detail fetch fails`() = runTest {
        source.enqueue(MockResponse().setResponseCode(404).setBody("{}"))
        val result = repo.openEpub(item(ino = null))
        assertTrue("Expected NetworkError but got: $result", result is EpubOpenResult.NetworkError)
    }

    // --- downloadEpub ---

    @Test
    fun `downloadEpub saves file to downloads store and returns Success`() = runTest {
        source.enqueue(MockResponse().setResponseCode(200).setBody(Buffer().write(epubBytes)))
        val result = repo.downloadEpub(item())
        assertTrue(result is EpubDownloadResult.Success)
        assertTrue(downloadsStore.get("source-1", "item-1") != null)
    }

    @Test
    fun `downloadEpub returns AlreadyDownloaded when file already in downloads store`() = runTest {
        downloadsStore.save("source-1", "item-1", epubBytes.inputStream())
        val result = repo.downloadEpub(item())
        assertEquals(0, source.requestCount)
        assertTrue(result is EpubDownloadResult.AlreadyDownloaded)
    }

    @Test
    fun `downloadEpub does not write to cache store`() = runTest {
        source.enqueue(MockResponse().setResponseCode(200).setBody(Buffer().write(epubBytes)))
        repo.downloadEpub(item())
        assertNull(cacheStore.get("source-1", "item-1"))
    }

    @Test
    fun `isDownloaded returns true after downloadEpub succeeds`() = runTest {
        source.enqueue(MockResponse().setResponseCode(200).setBody(Buffer().write(epubBytes)))
        repo.downloadEpub(item())
        assertTrue(repo.isDownloaded("source-1", "item-1"))
    }

    @Test
    fun `isCached returns true after openEpub populates cache`() = runTest {
        source.enqueue(MockResponse().setResponseCode(200).setBody(Buffer().write(epubBytes)))
        repo.openEpub(item())
        assertTrue(repo.isCached("source-1", "item-1"))
    }

    @Test
    fun `removeDownload deletes file from downloads store`() = runTest {
        downloadsStore.save("source-1", "item-1", epubBytes.inputStream())
        repo.removeDownload("source-1", "item-1")
        assertTrue(!repo.isDownloaded("source-1", "item-1"))
    }

    @Test
    fun `downloadEpub promotes cached file to downloads without network request`() = runTest {
        cacheStore.save("source-1", "item-1", epubBytes.inputStream())
        val result = repo.downloadEpub(item())
        assertEquals(0, source.requestCount)
        assertTrue(result is EpubDownloadResult.Success)
        assertTrue(downloadsStore.get("source-1", "item-1") != null)
        assertNull(cacheStore.get("source-1", "item-1"))
    }

    // ─── User-switch regression (item.sourceId ≠ activeServer.id) ─────────────────────────────
    //
    // A user-switch on the same URL mints a fresh SourceEntity UUID (SourceRepositoryImpl.commit)
    // while leaving the previous row and its cached files under cacheDir/epubs/<oldId>/ in place.
    // The item stays keyed by its original sourceId; the opener must too, or it looks in the wrong
    // directory, misses the truly-cached file, and falls into the network branch. That's the
    // "unable to resolve host" symptom on a cached book while offline.

    private fun multiTokenStorage(tokens: Map<String, String>): TokenStorage = object : TokenStorage {
        override suspend fun saveToken(sourceId: String, token: String) = Unit
        override suspend fun getToken(sourceId: String): String? = tokens[sourceId]
        override suspend fun deleteToken(sourceId: String) = Unit
    }

    private fun serverRow(id: String, baseUrl: String, isActive: Boolean) = Source(
        id = id,
        url = SourceUrl.parse(baseUrl)!!,
        isActive = isActive,
        insecureConnectionAllowed = false,
        username = "",
        serverType = ServerType.AUDIOBOOKSHELF,
    )

    private fun repoWith(sourceRepository: SourceRepository, tokenStorage: TokenStorage): EpubRepository =
        EpubRepositoryImpl(
            api = AbsApiClient(OkHttpClient(), DefaultDispatcherProvider),
            cacheStore = cacheStore,
            downloadsStore = downloadsStore,
            positionStore = positionStore,
            sourceRepository = sourceRepository,
            tokenStorage = tokenStorage,
        )

    @Test
    fun `openEpub after user switch resolves cached file under item's sourceId with zero network`() = runTest {
        val baseUrl = source.url("/").toString().trimEnd('/')
        val oldId = "old-user-source"
        val newId = "new-user-source"
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

        val result = repo.openEpub(item(sourceId = oldId))

        assertEquals("no network call should fire when cached file exists", 0, source.requestCount)
        assertTrue("Expected Success but got: $result", result is EpubOpenResult.Success)
    }

    @Test
    fun `openEpub after user switch resolves downloaded file under item's sourceId with zero network`() = runTest {
        val baseUrl = source.url("/").toString().trimEnd('/')
        val oldId = "old-user-source"
        val newId = "new-user-source"
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

        val result = repo.openEpub(item(sourceId = oldId))

        assertEquals(0, source.requestCount)
        assertTrue("Expected Success but got: $result", result is EpubOpenResult.Success)
    }

    @Test
    fun `openEpub cache miss hits the source matching item's sourceId not the active one`() = runTest {
        val activeMock = MockWebServer().also { it.start() }
        try {
            val activeBase = activeMock.url("/").toString().trimEnd('/')
            val itemBase = source.url("/").toString().trimEnd('/')
            val activeId = "active-source"
            val itemId = "item-source"
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
            source.enqueue(MockResponse().setResponseCode(200).setBody(Buffer().write(epubBytes)))

            val result = repo.openEpub(item(sourceId = itemId))

            assertTrue("Expected Success but got: $result", result is EpubOpenResult.Success)
            assertEquals("the item's own source must receive the request", 1, source.requestCount)
            assertEquals("the active-but-unrelated source must be untouched", 0, activeMock.requestCount)
            val recorded = source.takeRequest()
            assertEquals("Bearer item-token", recorded.getHeader("Authorization"))
        } finally {
            activeMock.shutdown()
        }
    }

    @Test
    fun `openEpub returns NetworkError when item's sourceId matches no source row`() = runTest {
        val baseUrl = source.url("/").toString().trimEnd('/')
        val repo = repoWith(
            multiServerRepository(
                servers = listOf(serverRow("some-other-source", baseUrl, isActive = true)),
                activeId = "some-other-source",
            ),
            multiTokenStorage(mapOf("some-other-source" to "some-token")),
        )

        val result = repo.openEpub(item(sourceId = "orphaned-source"))

        assertTrue("Expected NetworkError but got: $result", result is EpubOpenResult.NetworkError)
        assertEquals("no network call should fire when the source row is missing", 0, source.requestCount)
    }

    @Test
    fun `openEpub after user switch writes newly-fetched file under item's sourceId cache dir`() = runTest {
        val baseUrl = source.url("/").toString().trimEnd('/')
        val oldId = "old-user-source"
        val newId = "new-user-source"
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
        source.enqueue(MockResponse().setResponseCode(200).setBody(Buffer().write(epubBytes)))

        repo.openEpub(item(sourceId = oldId))

        assertNotNull("cache write must land under item.sourceId", cacheStore.get(oldId, "item-1"))
        assertNull("cache write must NOT land under the active source id", cacheStore.get(newId, "item-1"))
    }

    @Test
    fun `downloadEpub after user switch promotes cached file under item's sourceId with zero network`() = runTest {
        val baseUrl = source.url("/").toString().trimEnd('/')
        val oldId = "old-user-source"
        val newId = "new-user-source"
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

        val result = repo.downloadEpub(item(sourceId = oldId))

        assertEquals(0, source.requestCount)
        assertTrue("Expected Success but got: $result", result is EpubDownloadResult.Success)
        assertNotNull(downloadsStore.get(oldId, "item-1"))
        assertNull("original cache entry must be moved, not left behind", cacheStore.get(oldId, "item-1"))
    }

    @Test
    fun `downloadEpub cache miss hits the source matching item's sourceId not the active one`() = runTest {
        val activeMock = MockWebServer().also { it.start() }
        try {
            val activeBase = activeMock.url("/").toString().trimEnd('/')
            val itemBase = source.url("/").toString().trimEnd('/')
            val activeId = "active-source"
            val itemId = "item-source"
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
            source.enqueue(MockResponse().setResponseCode(200).setBody(Buffer().write(epubBytes)))

            val result = repo.downloadEpub(item(sourceId = itemId))

            assertTrue("Expected Success but got: $result", result is EpubDownloadResult.Success)
            assertEquals(1, source.requestCount)
            assertEquals(0, activeMock.requestCount)
            val recorded = source.takeRequest()
            assertEquals("Bearer item-token", recorded.getHeader("Authorization"))
            assertNotNull(downloadsStore.get(itemId, "item-1"))
        } finally {
            activeMock.shutdown()
        }
    }

    @Test
    fun `downloadEpub returns NetworkError when item's sourceId matches no source row and no cache`() = runTest {
        val baseUrl = source.url("/").toString().trimEnd('/')
        val repo = repoWith(
            multiServerRepository(
                servers = listOf(serverRow("some-other-source", baseUrl, isActive = true)),
                activeId = "some-other-source",
            ),
            multiTokenStorage(mapOf("some-other-source" to "some-token")),
        )

        val result = repo.downloadEpub(item(sourceId = "orphaned-source"))

        assertTrue("Expected NetworkError but got: $result", result is EpubDownloadResult.NetworkError)
        assertEquals(0, source.requestCount)
        assertFalse(repo.isDownloaded("orphaned-source", "item-1"))
    }

}
