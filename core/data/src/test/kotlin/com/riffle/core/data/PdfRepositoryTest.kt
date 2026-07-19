package com.riffle.core.data

import com.riffle.core.domain.DefaultDispatcherProvider

import com.riffle.core.models.EbookFormat
import com.riffle.core.models.LibraryItem
import com.riffle.core.domain.PdfDownloadResult
import com.riffle.core.domain.PdfOpenResult
import com.riffle.core.domain.PdfRepository
import com.riffle.core.domain.ReadingPositionStore
import com.riffle.core.models.Source
import com.riffle.core.domain.SourceRepository
import com.riffle.core.models.ServerType
import com.riffle.core.models.SourceUrl
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PdfRepositoryTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var source: MockWebServer
    private lateinit var cacheStore: LocalStoreImpl
    private lateinit var downloadsStore: LocalStoreImpl
    private lateinit var positionStore: FakePdfPositionStore
    private lateinit var repo: PdfRepository

    private val pdfBytes = "%PDF-1.4 fake pdf content\n%%EOF".toByteArray()

    @Before
    fun setUp() {
        source = MockWebServer()
        source.start()
        cacheStore = LocalStoreImpl(tmp.newFolder("pdf-cache"), ".pdf", com.riffle.core.domain.DefaultDispatcherProvider)
        downloadsStore = LocalStoreImpl(tmp.newFolder("pdf-downloads"), ".pdf", com.riffle.core.domain.DefaultDispatcherProvider)
        positionStore = FakePdfPositionStore()
        val sourceRepo = fakeServerRepository(source.url("/").toString().trimEnd('/'))
        repo = PdfRepositoryImpl(
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

    private class FakePdfPositionStore : ReadingPositionStore {
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
        title = "Test PDF",
        author = "Author",
        coverUrl = null,
        readingProgress = 0f,
        isCached = false,
        isDownloaded = false,
        ebookFormat = EbookFormat.Pdf,
        ebookFileIno = ino,
        sourceId = sourceId,
    )

    // --- openPdf ---

    @Test
    fun `openPdf with cache miss downloads file from source and returns Success`() = runTest {
        source.enqueue(MockResponse().setResponseCode(200).setBody(Buffer().write(pdfBytes)))
        val result = repo.openPdf(item())
        assertTrue(result is PdfOpenResult.Success)
    }

    @Test
    fun `openPdf with cache miss writes file to cache for subsequent opens`() = runTest {
        source.enqueue(MockResponse().setResponseCode(200).setBody(Buffer().write(pdfBytes)))
        repo.openPdf(item())
        assertTrue(cacheStore.get("source-1", "item-1") != null)
    }

    @Test
    fun `openPdf with cache hit makes no network request`() = runTest {
        cacheStore.save("source-1", "item-1", pdfBytes.inputStream())
        val result = repo.openPdf(item())
        assertEquals(0, source.requestCount)
        assertTrue(result is PdfOpenResult.Success)
    }

    @Test
    fun `openPdf with downloads hit makes no network request`() = runTest {
        downloadsStore.save("source-1", "item-1", pdfBytes.inputStream())
        val result = repo.openPdf(item())
        assertEquals(0, source.requestCount)
        assertTrue(result is PdfOpenResult.Success)
    }

    @Test
    fun `openPdf returns last saved reading position`() = runTest {
        cacheStore.save("source-1", "item-1", pdfBytes.inputStream())
        positionStore.save("source-1", "item-1", """{"href":"publication.pdf","type":"application/pdf","locations":{"position":5}}""")
        val result = repo.openPdf(item()) as PdfOpenResult.Success
        assertEquals("""{"href":"publication.pdf","type":"application/pdf","locations":{"position":5}}""", result.lastPosition)
    }

    @Test
    fun `openPdf returns null lastPosition for fresh pdf`() = runTest {
        cacheStore.save("source-1", "item-1", pdfBytes.inputStream())
        val result = repo.openPdf(item()) as PdfOpenResult.Success
        assertNull(result.lastPosition)
    }

    @Test
    fun `openPdf with null ebookFileIno fetches ino from item detail endpoint then downloads`() = runTest {
        source.enqueue(
            MockResponse().setResponseCode(200)
                .addHeader("Content-Type", "application/json")
                .setBody("""{"id":"item-1","libraryId":"lib-1","media":{"metadata":{"title":"T","authorName":"A"},"ebookFile":{"ino":"ino-42"}}}""")
        )
        source.enqueue(MockResponse().setResponseCode(200).setBody(Buffer().write(pdfBytes)))
        val result = repo.openPdf(item(ino = null))
        assertTrue("Expected Success but got: $result", result is PdfOpenResult.Success)
        assertEquals(2, source.requestCount)
    }

    @Test
    fun `openPdf with null ebookFileIno returns NetworkError when item detail fetch fails`() = runTest {
        source.enqueue(MockResponse().setResponseCode(404).setBody("{}"))
        val result = repo.openPdf(item(ino = null))
        assertTrue("Expected NetworkError but got: $result", result is PdfOpenResult.NetworkError)
    }

    // --- downloadPdf ---

    @Test
    fun `downloadPdf saves file to downloads store and returns Success`() = runTest {
        source.enqueue(MockResponse().setResponseCode(200).setBody(Buffer().write(pdfBytes)))
        val result = repo.downloadPdf(item())
        assertTrue(result is PdfDownloadResult.Success)
        assertTrue(downloadsStore.get("source-1", "item-1") != null)
    }

    @Test
    fun `downloadPdf returns AlreadyDownloaded when file already in downloads store`() = runTest {
        downloadsStore.save("source-1", "item-1", pdfBytes.inputStream())
        val result = repo.downloadPdf(item())
        assertEquals(0, source.requestCount)
        assertTrue(result is PdfDownloadResult.AlreadyDownloaded)
    }

    @Test
    fun `downloadPdf does not write to cache store`() = runTest {
        source.enqueue(MockResponse().setResponseCode(200).setBody(Buffer().write(pdfBytes)))
        repo.downloadPdf(item())
        assertNull(cacheStore.get("source-1", "item-1"))
    }

    @Test
    fun `isDownloaded returns true after downloadPdf succeeds`() = runTest {
        source.enqueue(MockResponse().setResponseCode(200).setBody(Buffer().write(pdfBytes)))
        repo.downloadPdf(item())
        assertTrue(repo.isDownloaded("source-1", "item-1"))
    }

    @Test
    fun `isCached returns true after openPdf populates cache`() = runTest {
        source.enqueue(MockResponse().setResponseCode(200).setBody(Buffer().write(pdfBytes)))
        repo.openPdf(item())
        assertTrue(repo.isCached("source-1", "item-1"))
    }

    @Test
    fun `removeDownload deletes file from downloads store`() = runTest {
        downloadsStore.save("source-1", "item-1", pdfBytes.inputStream())
        repo.removeDownload("source-1", "item-1")
        assertTrue(!repo.isDownloaded("source-1", "item-1"))
    }

    @Test
    fun `downloadPdf promotes cached file to downloads without network request`() = runTest {
        cacheStore.save("source-1", "item-1", pdfBytes.inputStream())
        val result = repo.downloadPdf(item())
        assertEquals(0, source.requestCount)
        assertTrue(result is PdfDownloadResult.Success)
        assertTrue(downloadsStore.get("source-1", "item-1") != null)
        assertNull(cacheStore.get("source-1", "item-1"))
    }

    // ─── User-switch regression (item.sourceId ≠ activeServer.id) ─────────────────────────────
    //
    // Mirror of the EPUB regression suite for the PDF opener. See EpubRepositoryTest for the full
    // rationale — a user-switch on the same URL leaves the previous source row (and its cached
    // files) in place; the opener must key by item.sourceId, not activeServer.id.

    private fun serverRow(id: String, baseUrl: String, isActive: Boolean) = Source(
        id = id,
        url = SourceUrl.parse(baseUrl)!!,
        isActive = isActive,
        insecureConnectionAllowed = false,
        username = "",
        serverType = ServerType.AUDIOBOOKSHELF,
    )

    private fun repoWith(sourceRepository: SourceRepository, tokens: Map<String, String>): PdfRepository =
        PdfRepositoryImpl(
            catalogRegistry = TestCatalogRegistry(sourceRepository, tokens),
            cacheStore = cacheStore,
            downloadsStore = downloadsStore,
            positionStore = positionStore,
            sourceRepository = sourceRepository,
        )

    @Test
    fun `openPdf after user switch resolves cached file under item's sourceId with zero network`() = runTest {
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
        cacheStore.save(oldId, "item-1", pdfBytes.inputStream())

        val result = repo.openPdf(item(sourceId = oldId))

        assertEquals("no network call should fire when cached file exists", 0, source.requestCount)
        assertTrue("Expected Success but got: $result", result is PdfOpenResult.Success)
    }

    @Test
    fun `openPdf after user switch resolves downloaded file under item's sourceId with zero network`() = runTest {
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
        downloadsStore.save(oldId, "item-1", pdfBytes.inputStream())

        val result = repo.openPdf(item(sourceId = oldId))

        assertEquals(0, source.requestCount)
        assertTrue("Expected Success but got: $result", result is PdfOpenResult.Success)
    }

    @Test
    fun `openPdf cache miss hits the source matching item's sourceId not the active one`() = runTest {
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
            source.enqueue(MockResponse().setResponseCode(200).setBody(Buffer().write(pdfBytes)))

            val result = repo.openPdf(item(sourceId = itemId))

            assertTrue("Expected Success but got: $result", result is PdfOpenResult.Success)
            assertEquals("the item's own source must receive the request", 1, source.requestCount)
            assertEquals("the active-but-unrelated source must be untouched", 0, activeMock.requestCount)
            val recorded = source.takeRequest()
            assertEquals("Bearer item-token", recorded.getHeader("Authorization"))
        } finally {
            activeMock.shutdown()
        }
    }

    @Test
    fun `openPdf returns NetworkError when item's sourceId matches no source row`() = runTest {
        val baseUrl = source.url("/").toString().trimEnd('/')
        val repo = repoWith(
            multiServerRepository(
                servers = listOf(serverRow("some-other-source", baseUrl, isActive = true)),
                activeId = "some-other-source",
            ),
            (mapOf("some-other-source" to "some-token")),
        )

        val result = repo.openPdf(item(sourceId = "orphaned-source"))

        assertTrue("Expected NetworkError but got: $result", result is PdfOpenResult.NetworkError)
        assertEquals(0, source.requestCount)
    }

    @Test
    fun `openPdf after user switch writes newly-fetched file under item's sourceId cache dir`() = runTest {
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
        source.enqueue(MockResponse().setResponseCode(200).setBody(Buffer().write(pdfBytes)))

        repo.openPdf(item(sourceId = oldId))

        assertNotNull("cache write must land under item.sourceId", cacheStore.get(oldId, "item-1"))
        assertNull("cache write must NOT land under the active source id", cacheStore.get(newId, "item-1"))
    }

    @Test
    fun `downloadPdf after user switch promotes cached file under item's sourceId with zero network`() = runTest {
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
        cacheStore.save(oldId, "item-1", pdfBytes.inputStream())

        val result = repo.downloadPdf(item(sourceId = oldId))

        assertEquals(0, source.requestCount)
        assertTrue("Expected Success but got: $result", result is PdfDownloadResult.Success)
        assertNotNull(downloadsStore.get(oldId, "item-1"))
        assertNull("original cache entry must be moved, not left behind", cacheStore.get(oldId, "item-1"))
    }

    @Test
    fun `downloadPdf cache miss hits the source matching item's sourceId not the active one`() = runTest {
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
            source.enqueue(MockResponse().setResponseCode(200).setBody(Buffer().write(pdfBytes)))

            val result = repo.downloadPdf(item(sourceId = itemId))

            assertTrue("Expected Success but got: $result", result is PdfDownloadResult.Success)
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
    fun `downloadPdf returns NetworkError when item's sourceId matches no source row and no cache`() = runTest {
        val baseUrl = source.url("/").toString().trimEnd('/')
        val repo = repoWith(
            multiServerRepository(
                servers = listOf(serverRow("some-other-source", baseUrl, isActive = true)),
                activeId = "some-other-source",
            ),
            (mapOf("some-other-source" to "some-token")),
        )

        val result = repo.downloadPdf(item(sourceId = "orphaned-source"))

        assertTrue("Expected NetworkError but got: $result", result is PdfDownloadResult.NetworkError)
        assertEquals(0, source.requestCount)
        assertFalse(repo.isDownloaded("orphaned-source", "item-1"))
    }
}
