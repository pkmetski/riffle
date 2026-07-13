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
import kotlinx.coroutines.async
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
        val sourceRepo = fakeServerRepository(source.url("/").toString().trimEnd('/'))
        repo = EpubRepositoryImpl(
            catalogRegistry = TestCatalogRegistry(sourceRepo, mapOf("source-1" to "test-token")),
            cacheStore = cacheStore,
            downloadsStore = downloadsStore,
            positionStore = positionStore,
            sourceRepository = sourceRepo,
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
        override suspend fun loadLastSyncedAt(sourceId: String, itemId: String): Long = 0L
        override suspend fun acceptServer(sourceId: String, itemId: String, payload: String, serverStamp: Long) { }
        override suspend fun markSyncedAt(sourceId: String, itemId: String, stamp: Long) { }
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

    // ─── Single-flight coalescing (Chitanka 429 regression) ───────────────────────────────
    //
    // Chitanka's Cloudflare layer rate-limits per source IP and 429s a duplicate concurrent
    // fetch of the same EPUB URL. Two paths reach openFile(): openEpub (from reader open /
    // TOC extraction) and downloadEpub (from user Download tap). Before the mutex, opening
    // the detail page fired openEpub for TOC extraction, and a same-instant Download tap
    // fired a second concurrent HTTP request — the second one 429'd. These tests pin the fix:
    // the second entrant waits on the (sourceId, itemId) mutex and reuses cached bytes on
    // release. Exactly ONE network request per shared item, no matter how many concurrent
    // openEpub/downloadEpub callers race.

    @Test
    fun `concurrent openEpub calls only issue one HTTP request`() = kotlinx.coroutines.runBlocking<Unit> {
        source.enqueue(
            MockResponse().setResponseCode(200).setBody(Buffer().write(epubBytes))
                .setBodyDelay(150, java.util.concurrent.TimeUnit.MILLISECONDS)
        )
        // No second response enqueued: if the mutex fails to coalesce, MockWebServer either
        // blocks forever or returns a 404 (dispatcher default). Either surfaces as a NetworkError
        // and the assertions below flip.
        val a = async(kotlinx.coroutines.Dispatchers.IO) { repo.openEpub(item()) }
        val b = async(kotlinx.coroutines.Dispatchers.IO) { repo.openEpub(item()) }
        val ra = a.await()
        val rb = b.await()
        assertTrue("first result should be Success but was $ra", ra is EpubOpenResult.Success)
        assertTrue("second result should be Success but was $rb", rb is EpubOpenResult.Success)
        assertEquals(1, source.requestCount)
    }

    @Test
    fun `concurrent openEpub + downloadEpub only issue one HTTP request`() = kotlinx.coroutines.runBlocking<Unit> {
        source.enqueue(
            MockResponse().setResponseCode(200).setBody(Buffer().write(epubBytes))
                .setBodyDelay(150, java.util.concurrent.TimeUnit.MILLISECONDS)
        )
        val opener = async(kotlinx.coroutines.Dispatchers.IO) { repo.openEpub(item()) }
        val downloader = async(kotlinx.coroutines.Dispatchers.IO) { repo.downloadEpub(item()) }
        val openResult = opener.await()
        val downloadResult = downloader.await()
        assertTrue("openEpub should Success but was $openResult", openResult is EpubOpenResult.Success)
        assertTrue("downloadEpub should Success but was $downloadResult", downloadResult is EpubDownloadResult.Success)
        assertEquals(1, source.requestCount)
        // Downloader is expected to see cache populated by openEpub (either pre-lock or post-lock)
        // and promote it into downloads. downloadsStore must have the bytes; cacheStore is cleared.
        assertTrue("bytes should land in downloadsStore", downloadsStore.get("source-1", "item-1") != null)
        assertNull(cacheStore.get("source-1", "item-1"))
    }

    @Test
    fun `concurrent downloadEpub calls only issue one HTTP request`() = kotlinx.coroutines.runBlocking<Unit> {
        source.enqueue(
            MockResponse().setResponseCode(200).setBody(Buffer().write(epubBytes))
                .setBodyDelay(150, java.util.concurrent.TimeUnit.MILLISECONDS)
        )
        val a = async(kotlinx.coroutines.Dispatchers.IO) { repo.downloadEpub(item()) }
        val b = async(kotlinx.coroutines.Dispatchers.IO) { repo.downloadEpub(item()) }
        val ra = a.await()
        val rb = b.await()
        // Whichever entrant wins the mutex fetches; the runner-up sees downloadsStore populated
        // and returns AlreadyDownloaded. Either terminal is valid — the invariant is that both
        // finish non-error AND exactly one HTTP request went out.
        val terminals = listOf(ra, rb)
        assertTrue("all terminals must Success/AlreadyDownloaded: $terminals", terminals.all {
            it is EpubDownloadResult.Success || it is EpubDownloadResult.AlreadyDownloaded
        })
        assertEquals(1, source.requestCount)
        assertTrue(downloadsStore.get("source-1", "item-1") != null)
    }

    @Test
    fun `downloadEpub after openEpub reuses cached bytes without network request`() = runTest {
        // Sequential — openEpub populates cache, then downloadEpub promotes it.
        source.enqueue(MockResponse().setResponseCode(200).setBody(Buffer().write(epubBytes)))
        repo.openEpub(item())
        val result = repo.downloadEpub(item())
        assertEquals("downloadEpub must NOT issue a second request", 1, source.requestCount)
        assertTrue(result is EpubDownloadResult.Success)
        assertTrue(downloadsStore.get("source-1", "item-1") != null)
    }

    @Test
    fun `openEpub after downloadEpub reads from downloadsStore without network request`() = runTest {
        source.enqueue(MockResponse().setResponseCode(200).setBody(Buffer().write(epubBytes)))
        repo.downloadEpub(item())
        val result = repo.openEpub(item())
        assertEquals("openEpub must NOT issue a second request", 1, source.requestCount)
        assertTrue(result is EpubOpenResult.Success)
    }

    // ─── User-switch regression (item.sourceId ≠ activeServer.id) ─────────────────────────────
    //
    // A user-switch on the same URL mints a fresh SourceEntity UUID (SourceRepositoryImpl.commit)
    // while leaving the previous row and its cached files under cacheDir/epubs/<oldId>/ in place.
    // The item stays keyed by its original sourceId; the opener must too, or it looks in the wrong
    // directory, misses the truly-cached file, and falls into the network branch. That's the
    // "unable to resolve host" symptom on a cached book while offline.

    private fun serverRow(id: String, baseUrl: String, isActive: Boolean) = Source(
        id = id,
        url = SourceUrl.parse(baseUrl)!!,
        isActive = isActive,
        insecureConnectionAllowed = false,
        username = "",
        serverType = ServerType.AUDIOBOOKSHELF,
    )

    private fun repoWith(sourceRepository: SourceRepository, tokens: Map<String, String>): EpubRepository =
        EpubRepositoryImpl(
            catalogRegistry = TestCatalogRegistry(sourceRepository, tokens),
            cacheStore = cacheStore,
            downloadsStore = downloadsStore,
            positionStore = positionStore,
            sourceRepository = sourceRepository,
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
            (mapOf(oldId to "old-token", newId to "new-token")),
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
            (mapOf(oldId to "old-token", newId to "new-token")),
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
                (mapOf(itemId to "item-token", activeId to "active-token")),
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
            (mapOf("some-other-source" to "some-token")),
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
            (mapOf(oldId to "old-token", newId to "new-token")),
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
            (mapOf(oldId to "old-token", newId to "new-token")),
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
                (mapOf(itemId to "item-token", activeId to "active-token")),
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
            (mapOf("some-other-source" to "some-token")),
        )

        val result = repo.downloadEpub(item(sourceId = "orphaned-source"))

        assertTrue("Expected NetworkError but got: $result", result is EpubDownloadResult.NetworkError)
        assertEquals(0, source.requestCount)
        assertFalse(repo.isDownloaded("orphaned-source", "item-1"))
    }

}
