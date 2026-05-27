package com.riffle.core.data

import com.riffle.core.domain.AddServerResult
import com.riffle.core.domain.Collection
import com.riffle.core.domain.LibraryItem
import com.riffle.core.domain.EbookFormat
import com.riffle.core.domain.LibraryRefreshResult
import com.riffle.core.domain.LibraryRepository
import com.riffle.core.domain.Server
import com.riffle.core.domain.ServerRepository
import com.riffle.core.domain.ServerUrl
import com.riffle.core.domain.TokenStorage
import com.riffle.core.network.AbsLibraryApi
import com.riffle.core.network.NetworkCollection
import com.riffle.core.network.NetworkCollectionResult
import com.riffle.core.network.NetworkCollectionWriteResult
import com.riffle.core.network.NetworkLibrariesResult
import com.riffle.core.network.NetworkLibraryItem
import com.riffle.core.network.NetworkLibraryItemsResult
import com.riffle.core.network.NetworkSeriesResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class ToReadRepositoryTest {

    private val activeServer = Server(
        id = "s1",
        url = ServerUrl.parse("http://abs.local")!!,
        displayName = "ABS",
        isActive = true,
        insecureConnectionAllowed = false,
        username = "u",
    )

    private fun makeRepo(
        api: AbsLibraryApi = FakeAbsApi(),
        libraryRepository: FakeLibraryRepository = FakeLibraryRepository(),
        serverRepository: ServerRepository = FakeServerRepository(activeServer),
        tokenStorage: TokenStorage = FakeTokenStorage(mutableMapOf("s1" to "tok")),
    ): ToReadRepositoryImpl = ToReadRepositoryImpl(
        api = api,
        serverRepository = serverRepository,
        tokenStorage = tokenStorage,
        libraryRepository = libraryRepository,
    )

    // ── isInToRead ────────────────────────────────────────────────────────────

    @Test
    fun `isInToRead returns true when book is in the cached To Read collection`() = runTest {
        val lib = FakeLibraryRepository()
        lib.setCollections("lib-1", listOf(collection("col-A", "To Read")))
        lib.setCollectionItems("col-A", listOf(item("item-1")))
        assertTrue(makeRepo(libraryRepository = lib).isInToRead("item-1", "lib-1"))
    }

    @Test
    fun `isInToRead returns false when cached To Read collection has no such book`() = runTest {
        val lib = FakeLibraryRepository()
        lib.setCollections("lib-1", listOf(collection("col-A", "To Read")))
        lib.setCollectionItems("col-A", listOf(item("item-9")))
        assertFalse(makeRepo(libraryRepository = lib).isInToRead("item-1", "lib-1"))
    }

    @Test
    fun `isInToRead returns false when no To Read collection is cached`() = runTest {
        val lib = FakeLibraryRepository()
        lib.setCollections("lib-1", emptyList())
        assertFalse(makeRepo(libraryRepository = lib).isInToRead("item-1", "lib-1"))
    }

    @Test
    fun `isInToRead does not hit the network`() = runTest {
        val api = FakeAbsApi()
        val lib = FakeLibraryRepository()
        lib.setCollections("lib-1", listOf(collection("col-A", "To Read")))
        lib.setCollectionItems("col-A", listOf(item("item-1")))
        makeRepo(api = api, libraryRepository = lib).isInToRead("item-1", "lib-1")
        assertEquals(0, api.getCollectionsCalls)
    }

    // ── addToToRead ───────────────────────────────────────────────────────────

    @Test
    fun `addToToRead adds to existing cached To Read collection without any GET`() = runTest {
        val api = FakeAbsApi()
        val lib = FakeLibraryRepository()
        lib.setCollections("lib-1", listOf(collection("col-A", "To Read")))
        val ok = makeRepo(api = api, libraryRepository = lib).addToToRead("item-1", "lib-1")
        assertTrue(ok)
        assertTrue(api.createCalls.isEmpty())
        assertEquals(listOf("col-A" to "item-1"), api.addCalls)
        assertEquals(0, api.getCollectionsCalls)
    }

    @Test
    fun `addToToRead falls back to server lookup when cache has no To Read`() = runTest {
        val api = FakeAbsApi(
            collectionsByLibrary = mapOf(
                "lib-1" to listOf(NetworkCollection("col-A", "lib-1", "To Read", emptyList())),
            ),
        )
        val lib = FakeLibraryRepository().apply { setCollections("lib-1", emptyList()) }
        val ok = makeRepo(api = api, libraryRepository = lib).addToToRead("item-1", "lib-1")
        assertTrue(ok)
        assertEquals(1, api.getCollectionsCalls)
        assertTrue(api.createCalls.isEmpty())
        assertEquals(listOf("col-A" to "item-1"), api.addCalls)
    }

    @Test
    fun `addToToRead creates the collection when neither cache nor server has To Read`() = runTest {
        val api = FakeAbsApi(collectionsByLibrary = mapOf("lib-1" to emptyList()))
        val lib = FakeLibraryRepository().apply { setCollections("lib-1", emptyList()) }
        val ok = makeRepo(api = api, libraryRepository = lib).addToToRead("item-1", "lib-1")
        assertTrue(ok)
        assertEquals(1, api.getCollectionsCalls)
        assertEquals(listOf(Triple("lib-1", "To Read", "item-1")), api.createCalls)
        assertTrue(api.addCalls.isEmpty())
    }

    @Test
    fun `addToToRead returns false when no active server`() = runTest {
        val repo = makeRepo(
            serverRepository = FakeServerRepository(activeServer = null),
            tokenStorage = FakeTokenStorage(mutableMapOf()),
        )
        assertFalse(repo.addToToRead("item-1", "lib-1"))
    }

    @Test
    fun `addToToRead refreshes collections after successful add`() = runTest {
        val lib = FakeLibraryRepository()
        lib.setCollections("lib-1", listOf(collection("col-A", "To Read")))
        makeRepo(libraryRepository = lib).addToToRead("item-1", "lib-1")
        assertEquals(listOf("lib-1"), lib.refreshCollectionsCalls)
    }

    @Test
    fun `addToToRead does not refresh collections when the write fails`() = runTest {
        val api = object : FakeAbsApi(collectionsByLibrary = mapOf("lib-1" to emptyList())) {
            override suspend fun createCollection(
                baseUrl: String, libraryId: String, name: String, initialBookId: String?,
                token: String, insecureAllowed: Boolean,
            ): NetworkCollectionWriteResult = NetworkCollectionWriteResult.NetworkError(IOException("HTTP 500"))
        }
        val lib = FakeLibraryRepository().apply { setCollections("lib-1", emptyList()) }
        makeRepo(api = api, libraryRepository = lib).addToToRead("item-1", "lib-1")
        assertTrue(lib.refreshCollectionsCalls.isEmpty())
    }

    @Test
    fun `addToToRead returns false when create fails`() = runTest {
        val api = object : FakeAbsApi(collectionsByLibrary = mapOf("lib-1" to emptyList())) {
            override suspend fun createCollection(
                baseUrl: String, libraryId: String, name: String, initialBookId: String?,
                token: String, insecureAllowed: Boolean,
            ): NetworkCollectionWriteResult = NetworkCollectionWriteResult.NetworkError(IOException("HTTP 500"))
        }
        val lib = FakeLibraryRepository().apply { setCollections("lib-1", emptyList()) }
        assertFalse(makeRepo(api = api, libraryRepository = lib).addToToRead("item-1", "lib-1"))
    }

    @Test
    fun `addToToRead returns false when add fails`() = runTest {
        val api = object : FakeAbsApi() {
            override suspend fun addBookToCollection(
                baseUrl: String, collectionId: String, libraryItemId: String,
                token: String, insecureAllowed: Boolean,
            ): NetworkCollectionWriteResult = NetworkCollectionWriteResult.NetworkError(IOException("HTTP 500"))
        }
        val lib = FakeLibraryRepository()
        lib.setCollections("lib-1", listOf(collection("col-A", "To Read")))
        assertFalse(makeRepo(api = api, libraryRepository = lib).addToToRead("item-1", "lib-1"))
    }

    // ── removeFromToRead ──────────────────────────────────────────────────────

    @Test
    fun `removeFromToRead calls DELETE when cached collection exists`() = runTest {
        val api = FakeAbsApi()
        val lib = FakeLibraryRepository()
        lib.setCollections("lib-1", listOf(collection("col-A", "To Read")))
        val ok = makeRepo(api = api, libraryRepository = lib).removeFromToRead("item-1", "lib-1")
        assertTrue(ok)
        assertEquals(listOf("col-A" to "item-1"), api.removeCalls)
        assertEquals(0, api.getCollectionsCalls)
    }

    @Test
    fun `removeFromToRead returns true and makes no call when no cached To Read collection`() = runTest {
        val api = FakeAbsApi()
        val lib = FakeLibraryRepository().apply { setCollections("lib-1", emptyList()) }
        val ok = makeRepo(api = api, libraryRepository = lib).removeFromToRead("item-1", "lib-1")
        assertTrue(ok)
        assertTrue(api.removeCalls.isEmpty())
        assertEquals(0, api.getCollectionsCalls)
    }

    @Test
    fun `removeFromToRead returns false when remove fails`() = runTest {
        val api = object : FakeAbsApi() {
            override suspend fun removeBookFromCollection(
                baseUrl: String, collectionId: String, libraryItemId: String,
                token: String, insecureAllowed: Boolean,
            ): NetworkCollectionWriteResult = NetworkCollectionWriteResult.NetworkError(IOException("HTTP 500"))
        }
        val lib = FakeLibraryRepository()
        lib.setCollections("lib-1", listOf(collection("col-A", "To Read")))
        assertFalse(makeRepo(api = api, libraryRepository = lib).removeFromToRead("item-1", "lib-1"))
    }

    @Test
    fun `removeFromToRead refreshes collections after successful remove`() = runTest {
        val lib = FakeLibraryRepository()
        lib.setCollections("lib-1", listOf(collection("col-A", "To Read")))
        makeRepo(libraryRepository = lib).removeFromToRead("item-1", "lib-1")
        assertEquals(listOf("lib-1"), lib.refreshCollectionsCalls)
    }

    @Test
    fun `removeFromToRead does not refresh when collection is missing`() = runTest {
        val lib = FakeLibraryRepository().apply { setCollections("lib-1", emptyList()) }
        makeRepo(libraryRepository = lib).removeFromToRead("item-1", "lib-1")
        assertTrue(lib.refreshCollectionsCalls.isEmpty())
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private fun collection(id: String, name: String) =
        Collection(id = id, libraryId = "lib-1", name = name, bookCount = 0)

    private fun item(id: String) = LibraryItem(
        id = id,
        libraryId = "lib-1",
        title = "T",
        author = "A",
        coverUrl = null,
        readingProgress = 0f,
        isCached = false,
        isDownloaded = false,
        ebookFormat = EbookFormat.Epub,
    )
}

private class FakeServerRepository(private val activeServer: Server?) : ServerRepository {
    override fun observeAll() = MutableStateFlow(listOfNotNull(activeServer))
    override suspend fun getActive(): Server? = activeServer
    override suspend fun addServer(url: ServerUrl, username: String, password: String, insecureAllowed: Boolean): AddServerResult =
        AddServerResult.NetworkError(IOException())
    override suspend fun setActive(serverId: String) {}
    override suspend fun remove(serverId: String) {}
    override suspend fun getServerVersion(serverId: String): String? = null
}

private class FakeTokenStorage(private val tokens: MutableMap<String, String>) : TokenStorage {
    override suspend fun saveToken(serverId: String, token: String) { tokens[serverId] = token }
    override suspend fun getToken(serverId: String): String? = tokens[serverId]
    override suspend fun deleteToken(serverId: String) { tokens.remove(serverId) }
}

private class FakeLibraryRepository : LibraryRepository {
    val refreshCollectionsCalls = mutableListOf<String>()
    private val collectionsByLibrary = mutableMapOf<String, MutableStateFlow<List<Collection>>>()
    private val itemsByCollection = mutableMapOf<String, MutableStateFlow<List<LibraryItem>>>()

    fun setCollections(libraryId: String, cols: List<Collection>) {
        collectionsByLibrary.getOrPut(libraryId) { MutableStateFlow(emptyList()) }.value = cols
    }

    fun setCollectionItems(collectionId: String, items: List<LibraryItem>) {
        itemsByCollection.getOrPut(collectionId) { MutableStateFlow(emptyList()) }.value = items
    }

    override suspend fun refreshCollections(libraryId: String): LibraryRefreshResult {
        refreshCollectionsCalls += libraryId
        return LibraryRefreshResult.Success
    }

    override fun observeCollections(libraryId: String) =
        collectionsByLibrary.getOrPut(libraryId) { MutableStateFlow(emptyList()) }

    override fun observeCollectionItems(collectionId: String) =
        itemsByCollection.getOrPut(collectionId) { MutableStateFlow(emptyList()) }

    override fun observeLibraries() = flowOf(emptyList<com.riffle.core.domain.Library>())
    override fun observeLibraryItems(libraryId: String) = flowOf(emptyList<LibraryItem>())
    override fun observeUngroupedLibraryItems(libraryId: String) = flowOf(emptyList<LibraryItem>())
    override fun observeInProgressItems(libraryId: String) = flowOf(emptyList<LibraryItem>())
    override fun observeFinishedItems(libraryId: String) = flowOf(emptyList<LibraryItem>())
    override fun observeRecentlyAddedItems(libraryId: String) = flowOf(emptyList<LibraryItem>())
    override fun observeAllBooks(libraryId: String) = flowOf(emptyList<LibraryItem>())
    override fun observeSeries(libraryId: String) = flowOf(emptyList<com.riffle.core.domain.Series>())
    override fun observeSeriesItems(seriesId: String) = flowOf(emptyList<LibraryItem>())
    override suspend fun getItem(itemId: String): LibraryItem? = null
    override suspend fun markItemOpened(itemId: String) {}
    override suspend fun updateReadingProgress(itemId: String, progress: Float) {}
    override suspend fun refreshLibraries(): LibraryRefreshResult = LibraryRefreshResult.Success
    override suspend fun refreshLibraryItems(libraryId: String): LibraryRefreshResult = LibraryRefreshResult.Success
    override suspend fun refreshSeries(libraryId: String): LibraryRefreshResult = LibraryRefreshResult.Success
}

private open class FakeAbsApi(
    val collectionsByLibrary: Map<String, List<NetworkCollection>> = emptyMap(),
) : AbsLibraryApi {
    val createCalls = mutableListOf<Triple<String, String, String?>>()
    val addCalls = mutableListOf<Pair<String, String>>()
    val removeCalls = mutableListOf<Pair<String, String>>()
    var getCollectionsCalls = 0
        private set

    override suspend fun getLibraries(baseUrl: String, token: String, insecureAllowed: Boolean): NetworkLibrariesResult =
        NetworkLibrariesResult.Success(emptyList())

    override suspend fun getLibraryItems(baseUrl: String, libraryId: String, token: String, insecureAllowed: Boolean): NetworkLibraryItemsResult =
        NetworkLibraryItemsResult.Success(emptyList())

    override suspend fun getSeries(baseUrl: String, libraryId: String, token: String, insecureAllowed: Boolean): NetworkSeriesResult =
        NetworkSeriesResult.Success(emptyList())

    override suspend fun getCollections(baseUrl: String, libraryId: String, token: String, insecureAllowed: Boolean): NetworkCollectionResult {
        getCollectionsCalls++
        return NetworkCollectionResult.Success(collectionsByLibrary[libraryId].orEmpty())
    }

    override suspend fun createCollection(
        baseUrl: String, libraryId: String, name: String, initialBookId: String?,
        token: String, insecureAllowed: Boolean,
    ): NetworkCollectionWriteResult {
        createCalls += Triple(libraryId, name, initialBookId)
        return NetworkCollectionWriteResult.Success(NetworkCollection("col-new", libraryId, name, emptyList()))
    }

    override suspend fun addBookToCollection(
        baseUrl: String, collectionId: String, libraryItemId: String,
        token: String, insecureAllowed: Boolean,
    ): NetworkCollectionWriteResult {
        addCalls += collectionId to libraryItemId
        return NetworkCollectionWriteResult.Success(null)
    }

    override suspend fun removeBookFromCollection(
        baseUrl: String, collectionId: String, libraryItemId: String,
        token: String, insecureAllowed: Boolean,
    ): NetworkCollectionWriteResult {
        removeCalls += collectionId to libraryItemId
        return NetworkCollectionWriteResult.Success(null)
    }
}
