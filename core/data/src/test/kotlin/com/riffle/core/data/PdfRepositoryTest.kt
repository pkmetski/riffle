package com.riffle.core.data

import com.riffle.core.domain.EbookFormat
import com.riffle.core.domain.LibraryItem
import com.riffle.core.domain.PdfOpenResult
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

class PdfRepositoryTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var server: MockWebServer
    private lateinit var cacheManager: PdfCacheManagerImpl
    private lateinit var positionStore: FakePdfPositionStore
    private lateinit var repo: PdfRepositoryImpl

    private val pdfBytes = "%PDF-1.4 fake pdf content\n%%EOF".toByteArray()

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        cacheManager = PdfCacheManagerImpl(tmp.newFolder("pdf-cache"))
        positionStore = FakePdfPositionStore()
        repo = PdfRepositoryImpl(
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

    private class FakePdfPositionStore : ReadingPositionStore {
        val store = mutableMapOf<String, String>()
        override suspend fun save(itemId: String, cfi: String) { store[itemId] = cfi }
        override suspend fun load(itemId: String): String? = store[itemId]
    }

    private fun item(id: String = "item-1", ino: String? = "ino-42") = LibraryItem(
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
    )

    @Test
    fun `openPdf with cache miss downloads file from server and returns Success`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody(Buffer().write(pdfBytes)))
        val result = repo.openPdf(item())
        assertTrue(result is PdfOpenResult.Success)
    }

    @Test
    fun `openPdf with cache miss writes file to cache for subsequent opens`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody(Buffer().write(pdfBytes)))
        repo.openPdf(item())
        assertTrue(cacheManager.getCachedPdf("item-1") != null)
    }

    @Test
    fun `openPdf with cache hit makes no network request`() = runTest {
        cacheManager.cachePdf("item-1", pdfBytes.inputStream())
        val result = repo.openPdf(item())
        assertEquals(0, server.requestCount)
        assertTrue(result is PdfOpenResult.Success)
    }

    @Test
    fun `openPdf returns last saved reading position`() = runTest {
        cacheManager.cachePdf("item-1", pdfBytes.inputStream())
        positionStore.save("item-1", """{"href":"publication.pdf","type":"application/pdf","locations":{"position":5}}""")
        val result = repo.openPdf(item()) as PdfOpenResult.Success
        assertEquals("""{"href":"publication.pdf","type":"application/pdf","locations":{"position":5}}""", result.lastPosition)
    }

    @Test
    fun `openPdf returns null lastPosition for fresh pdf`() = runTest {
        cacheManager.cachePdf("item-1", pdfBytes.inputStream())
        val result = repo.openPdf(item()) as PdfOpenResult.Success
        assertNull(result.lastPosition)
    }

    @Test
    fun `openPdf with null ebookFileIno fetches ino from item detail endpoint then downloads`() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(200)
                .addHeader("Content-Type", "application/json")
                .setBody("""{"id":"item-1","libraryId":"lib-1","media":{"metadata":{"title":"T","authorName":"A"},"ebookFile":{"ino":"ino-42"}}}""")
        )
        server.enqueue(MockResponse().setResponseCode(200).setBody(Buffer().write(pdfBytes)))
        val result = repo.openPdf(item(ino = null))
        assertTrue("Expected Success but got: $result", result is PdfOpenResult.Success)
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `openPdf with null ebookFileIno returns NetworkError when item detail fetch fails`() = runTest {
        server.enqueue(MockResponse().setResponseCode(404).setBody("{}"))
        val result = repo.openPdf(item(ino = null))
        assertTrue("Expected NetworkError but got: $result", result is PdfOpenResult.NetworkError)
    }
}
