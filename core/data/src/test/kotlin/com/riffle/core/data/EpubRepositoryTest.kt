package com.riffle.core.data

import com.riffle.core.domain.EbookFormat
import com.riffle.core.domain.EpubDownloadResult
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
    private lateinit var cacheStore: LocalStoreImpl
    private lateinit var downloadsStore: LocalStoreImpl
    private lateinit var positionStore: FakePositionStore
    private lateinit var repo: EpubRepositoryImpl

    private val epubBytes = "PK fake epub content".toByteArray()

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        cacheStore = LocalStoreImpl(tmp.newFolder("cache"), ".epub")
        downloadsStore = LocalStoreImpl(tmp.newFolder("downloads"), ".epub")
        positionStore = FakePositionStore()
        repo = EpubRepositoryImpl(
            api = AbsApiClient(OkHttpClient()),
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
        override suspend fun loadLocalUpdatedAt(itemId: String): Long = 0L
        override suspend fun updateLocalTimestamp(itemId: String, millis: Long) = Unit
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
        ebookFormat = EbookFormat.Epub,
        ebookFileIno = ino,
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
        assertTrue(cacheStore.get("item-1") != null)
    }

    @Test
    fun `openEpub with cache hit makes no network request`() = runTest {
        cacheStore.save("item-1", epubBytes.inputStream())
        val result = repo.openEpub(item())
        assertEquals(0, server.requestCount)
        assertTrue(result is EpubOpenResult.Success)
    }

    @Test
    fun `openEpub with downloads hit makes no network request`() = runTest {
        downloadsStore.save("item-1", epubBytes.inputStream())
        val result = repo.openEpub(item())
        assertEquals(0, server.requestCount)
        assertTrue(result is EpubOpenResult.Success)
    }

    @Test
    fun `openEpub returns last saved reading position`() = runTest {
        cacheStore.save("item-1", epubBytes.inputStream())
        positionStore.save("item-1", "epubcfi(/6/4!/4/1:0)")
        val result = repo.openEpub(item()) as EpubOpenResult.Success
        assertEquals("epubcfi(/6/4!/4/1:0)", result.lastPosition)
    }

    @Test
    fun `openEpub returns null lastPosition for fresh book`() = runTest {
        cacheStore.save("item-1", epubBytes.inputStream())
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
        assertTrue(downloadsStore.get("item-1") != null)
    }

    @Test
    fun `downloadEpub returns AlreadyDownloaded when file already in downloads store`() = runTest {
        downloadsStore.save("item-1", epubBytes.inputStream())
        val result = repo.downloadEpub(item())
        assertEquals(0, server.requestCount)
        assertTrue(result is EpubDownloadResult.AlreadyDownloaded)
    }

    @Test
    fun `downloadEpub does not write to cache store`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody(Buffer().write(epubBytes)))
        repo.downloadEpub(item())
        assertNull(cacheStore.get("item-1"))
    }

    @Test
    fun `isDownloaded returns true after downloadEpub succeeds`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody(Buffer().write(epubBytes)))
        repo.downloadEpub(item())
        assertTrue(repo.isDownloaded("item-1"))
    }

    @Test
    fun `isCached returns true after openEpub populates cache`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody(Buffer().write(epubBytes)))
        repo.openEpub(item())
        assertTrue(repo.isCached("item-1"))
    }

    @Test
    fun `removeDownload deletes file from downloads store`() = runTest {
        downloadsStore.save("item-1", epubBytes.inputStream())
        repo.removeDownload("item-1")
        assertTrue(!repo.isDownloaded("item-1"))
    }

    @Test
    fun `downloadEpub promotes cached file to downloads without network request`() = runTest {
        cacheStore.save("item-1", epubBytes.inputStream())
        val result = repo.downloadEpub(item())
        assertEquals(0, server.requestCount)
        assertTrue(result is EpubDownloadResult.Success)
        assertTrue(downloadsStore.get("item-1") != null)
        assertNull(cacheStore.get("item-1"))
    }
}
