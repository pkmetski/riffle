package com.riffle.core.data

import com.riffle.core.domain.EbookFormat
import com.riffle.core.domain.EpubDownloadResult
import com.riffle.core.domain.EpubOpenResult
import com.riffle.core.domain.LibraryItem
import com.riffle.core.domain.ReadingPositionStore
import com.riffle.core.domain.Server
import com.riffle.core.domain.ServerRepository
import com.riffle.core.domain.ServerType
import com.riffle.core.domain.ServerUrl
import com.riffle.core.domain.TokenStorage
import com.riffle.core.network.AbsApiClient
import com.riffle.core.network.NetworkStorytellerBundleResult
import com.riffle.core.network.StorytellerBundleApi
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
            bundleFetcher = EpubBundleFetcher(
                api = StorytellerBundleApi { _, _, _, _ -> error("ABS test should not call bundle fetcher") },
                workingDirProvider = { tmp.newFolder("unused-${System.nanoTime()}") },
            ),
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
            isActive = true,
            insecureConnectionAllowed = false,
            username = "",
        )
        override fun observeAll(): Flow<List<Server>> = flowOf(listOf(activeServer))
        override suspend fun getActive(): Server = activeServer
        override suspend fun authenticate(url: ServerUrl, username: String, password: String, insecureAllowed: Boolean, serverType: com.riffle.core.domain.ServerType) =
            throw UnsupportedOperationException()
        override suspend fun commit(pending: com.riffle.core.domain.PendingServer, hiddenLibraryIds: Set<String>) =
            throw UnsupportedOperationException()
        override suspend fun setActive(serverId: String) = Unit
        override suspend fun remove(serverId: String) = Unit
        override suspend fun getServerVersion(serverId: String): String? = null
    }

    private fun fakeServerRepositoryForServer(activeServer: Server): ServerRepository = object : ServerRepository {
        override fun observeAll(): Flow<List<Server>> = flowOf(listOf(activeServer))
        override suspend fun getActive(): Server = activeServer
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
        override suspend fun save(serverId: String, itemId: String, cfi: String) {
            store[serverId to itemId] = cfi
        }
        override suspend fun load(serverId: String, itemId: String): String? = store[serverId to itemId]
        override suspend fun loadLocalUpdatedAt(serverId: String, itemId: String): Long = 0L
        override suspend fun updateLocalTimestamp(serverId: String, itemId: String, millis: Long) = Unit
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
        positionStore.save("server-1", "item-1", "epubcfi(/6/4!/4/1:0)")
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

    // --- Storyteller ---

    @Test
    fun `openEpub for Storyteller server uses bundle fetcher and caches extracted epub`() = runTest {
        val epubContent = "STORY EPUB".toByteArray()
        val bundleBytes = java.io.ByteArrayOutputStream().also { baos ->
            java.util.zip.ZipOutputStream(baos).use { zip ->
                zip.putNextEntry(java.util.zip.ZipEntry("book.epub"))
                zip.write(epubContent)
                zip.closeEntry()
            }
        }.toByteArray()

        val bundleApi = StorytellerBundleApi { _, bookId, token, _ ->
            assertEquals("42", bookId)
            assertEquals("st-token", token)
            NetworkStorytellerBundleResult.Success(
                bundleBytes.toResponseBody("application/zip".toMediaType()),
            )
        }
        val fetcher = EpubBundleFetcher(bundleApi, workingDirProvider = { tmp.newFolder("wd-${System.nanoTime()}") })
        val storytellerCache = LocalStoreImpl(tmp.newFolder("st-cache"), ".epub")
        val storytellerDownloads = LocalStoreImpl(tmp.newFolder("st-dl"), ".epub")
        val storytellerServer = Server(
            id = "st-srv",
            url = ServerUrl.parse("http://st.example")!!,
            displayName = "St",
            isActive = true,
            insecureConnectionAllowed = false,
            username = "x",
            serverType = ServerType.STORYTELLER,
        )
        val storytellerRepo = EpubRepositoryImpl(
            api = AbsApiClient(OkHttpClient()),
            bundleFetcher = fetcher,
            cacheStore = storytellerCache,
            downloadsStore = storytellerDownloads,
            positionStore = FakePositionStore(),
            serverRepository = fakeServerRepositoryForServer(storytellerServer),
            tokenStorage = fakeTokenStorage("st-token"),
        )

        val result = storytellerRepo.openEpub(item(id = "42", ino = null))

        assertTrue("Expected Success but got $result", result is EpubOpenResult.Success)
        val cached = storytellerCache.get("42")
        assertEquals(epubContent.toList(), cached!!.readBytes().toList())
    }
}
