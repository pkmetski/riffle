package com.riffle.core.data

import com.riffle.core.domain.AddServerResult
import com.riffle.core.domain.EbookFormat
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
        api: AbsLibraryApi,
        libraryRepository: FakeLibraryRepository = FakeLibraryRepository(),
    ): ToReadRepositoryImpl =
        ToReadRepositoryImpl(
            api = api,
            serverRepository = FakeServerRepository(activeServer),
            tokenStorage = FakeTokenStorage(mutableMapOf("s1" to "tok")),
            libraryRepository = libraryRepository,
        )

    @Test
    fun `isInToRead returns true when book is in the To Read collection`() = runTest {
        val api = FakeAbsApi(
            collectionsByLibrary = mapOf(
                "lib-1" to listOf(
                    NetworkCollection("col-A", "lib-1", "To Read", listOf(stubItem("item-1"))),
                ),
            ),
        )
        assertTrue(makeRepo(api).isInToRead("item-1", "lib-1"))
    }

    @Test
    fun `isInToRead returns false when To Read collection has no such book`() = runTest {
        val api = FakeAbsApi(
            collectionsByLibrary = mapOf(
                "lib-1" to listOf(
                    NetworkCollection("col-A", "lib-1", "To Read", listOf(stubItem("item-9"))),
                ),
            ),
        )
        assertFalse(makeRepo(api).isInToRead("item-1", "lib-1"))
    }

    @Test
    fun `isInToRead returns false when no To Read collection exists`() = runTest {
        val api = FakeAbsApi(collectionsByLibrary = mapOf("lib-1" to emptyList()))
        assertFalse(makeRepo(api).isInToRead("item-1", "lib-1"))
    }

    @Test
    fun `isInToRead returns false when no active server`() = runTest {
        val repo = ToReadRepositoryImpl(
            api = FakeAbsApi(),
            serverRepository = FakeServerRepository(activeServer = null),
            tokenStorage = FakeTokenStorage(mutableMapOf()),
            libraryRepository = FakeLibraryRepository(),
        )
        assertFalse(repo.isInToRead("item-1", "lib-1"))
    }

    @Test
    fun `addToToRead creates the collection with the book when no To Read exists`() = runTest {
        val api = FakeAbsApi(collectionsByLibrary = mapOf("lib-1" to emptyList()))
        val ok = makeRepo(api).addToToRead("item-1", "lib-1")
        assertTrue(ok)
        assertEquals(listOf(Triple("lib-1", "To Read", "item-1")), api.createCalls)
        assertTrue(api.addCalls.isEmpty())
    }

    @Test
    fun `addToToRead adds to existing To Read collection`() = runTest {
        val api = FakeAbsApi(
            collectionsByLibrary = mapOf(
                "lib-1" to listOf(NetworkCollection("col-A", "lib-1", "To Read", emptyList())),
            ),
        )
        val ok = makeRepo(api).addToToRead("item-1", "lib-1")
        assertTrue(ok)
        assertTrue(api.createCalls.isEmpty())
        assertEquals(listOf("col-A" to "item-1"), api.addCalls)
    }

    @Test
    fun `addToToRead returns false when no active server`() = runTest {
        val repo = ToReadRepositoryImpl(
            api = FakeAbsApi(),
            serverRepository = FakeServerRepository(activeServer = null),
            tokenStorage = FakeTokenStorage(mutableMapOf()),
            libraryRepository = FakeLibraryRepository(),
        )
        assertFalse(repo.addToToRead("item-1", "lib-1"))
    }

    @Test
    fun `addToToRead refreshes collections after successful add`() = runTest {
        val api = FakeAbsApi(collectionsByLibrary = mapOf("lib-1" to listOf(NetworkCollection("col-A", "lib-1", "To Read", emptyList()))))
        val libRepo = FakeLibraryRepository()
        makeRepo(api, libRepo).addToToRead("item-1", "lib-1")
        assertEquals(listOf("lib-1"), libRepo.refreshCollectionsCalls)
    }

    @Test
    fun `addToToRead does not refresh collections when the write fails`() = runTest {
        val api = object : FakeAbsApi(collectionsByLibrary = mapOf("lib-1" to emptyList())) {
            override suspend fun createCollection(baseUrl: String, libraryId: String, name: String, initialBookId: String?, token: String, insecureAllowed: Boolean): NetworkCollectionWriteResult =
                NetworkCollectionWriteResult.NetworkError(java.io.IOException("HTTP 500"))
        }
        val libRepo = FakeLibraryRepository()
        makeRepo(api, libRepo).addToToRead("item-1", "lib-1")
        assertTrue(libRepo.refreshCollectionsCalls.isEmpty())
    }

    @Test
    fun `removeFromToRead refreshes collections after successful remove`() = runTest {
        val api = FakeAbsApi(
            collectionsByLibrary = mapOf(
                "lib-1" to listOf(NetworkCollection("col-A", "lib-1", "To Read", listOf(stubItem("item-1")))),
            ),
        )
        val libRepo = FakeLibraryRepository()
        makeRepo(api, libRepo).removeFromToRead("item-1", "lib-1")
        assertEquals(listOf("lib-1"), libRepo.refreshCollectionsCalls)
    }

    @Test
    fun `removeFromToRead does not refresh when collection is missing`() = runTest {
        val api = FakeAbsApi(collectionsByLibrary = mapOf("lib-1" to emptyList()))
        val libRepo = FakeLibraryRepository()
        makeRepo(api, libRepo).removeFromToRead("item-1", "lib-1")
        assertTrue(libRepo.refreshCollectionsCalls.isEmpty())
    }

    @Test
    fun `addToToRead returns false when create fails`() = runTest {
        val api = object : FakeAbsApi(collectionsByLibrary = mapOf("lib-1" to emptyList())) {
            override suspend fun createCollection(baseUrl: String, libraryId: String, name: String, initialBookId: String?, token: String, insecureAllowed: Boolean): NetworkCollectionWriteResult =
                NetworkCollectionWriteResult.NetworkError(java.io.IOException("HTTP 500"))
        }
        assertFalse(makeRepo(api).addToToRead("item-1", "lib-1"))
    }

    @Test
    fun `addToToRead returns false when add fails`() = runTest {
        val api = object : FakeAbsApi(collectionsByLibrary = mapOf("lib-1" to listOf(NetworkCollection("col-A", "lib-1", "To Read", emptyList())))) {
            override suspend fun addBookToCollection(baseUrl: String, collectionId: String, libraryItemId: String, token: String, insecureAllowed: Boolean): NetworkCollectionWriteResult =
                NetworkCollectionWriteResult.NetworkError(java.io.IOException("HTTP 500"))
        }
        assertFalse(makeRepo(api).addToToRead("item-1", "lib-1"))
    }

    @Test
    fun `removeFromToRead calls DELETE when collection exists`() = runTest {
        val api = FakeAbsApi(
            collectionsByLibrary = mapOf(
                "lib-1" to listOf(NetworkCollection("col-A", "lib-1", "To Read", listOf(stubItem("item-1")))),
            ),
        )
        val ok = makeRepo(api).removeFromToRead("item-1", "lib-1")
        assertTrue(ok)
        assertEquals(listOf("col-A" to "item-1"), api.removeCalls)
    }

    @Test
    fun `removeFromToRead returns true and makes no call when no To Read collection`() = runTest {
        val api = FakeAbsApi(collectionsByLibrary = mapOf("lib-1" to emptyList()))
        val ok = makeRepo(api).removeFromToRead("item-1", "lib-1")
        assertTrue(ok)
        assertTrue(api.removeCalls.isEmpty())
    }

    @Test
    fun `removeFromToRead returns false when remove fails`() = runTest {
        val api = object : FakeAbsApi(collectionsByLibrary = mapOf("lib-1" to listOf(NetworkCollection("col-A", "lib-1", "To Read", listOf(stubItem("item-1")))))) {
            override suspend fun removeBookFromCollection(baseUrl: String, collectionId: String, libraryItemId: String, token: String, insecureAllowed: Boolean): NetworkCollectionWriteResult =
                NetworkCollectionWriteResult.NetworkError(java.io.IOException("HTTP 500"))
        }
        assertFalse(makeRepo(api).removeFromToRead("item-1", "lib-1"))
    }

    private fun stubItem(id: String) = NetworkLibraryItem(
        id = id,
        libraryId = "lib-1",
        title = "T",
        author = "A",
        readingProgress = null,
        ebookFormat = EbookFormat.Epub,
        ebookFileIno = null,
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

private class FakeLibraryRepository : com.riffle.core.domain.LibraryRepository {
    val refreshCollectionsCalls = mutableListOf<String>()
    override suspend fun refreshCollections(libraryId: String): com.riffle.core.domain.LibraryRefreshResult {
        refreshCollectionsCalls += libraryId
        return com.riffle.core.domain.LibraryRefreshResult.Success
    }
    override fun observeLibraries() = kotlinx.coroutines.flow.flowOf(emptyList<com.riffle.core.domain.Library>())
    override fun observeLibraryItems(libraryId: String) = kotlinx.coroutines.flow.flowOf(emptyList<com.riffle.core.domain.LibraryItem>())
    override fun observeUngroupedLibraryItems(libraryId: String) = kotlinx.coroutines.flow.flowOf(emptyList<com.riffle.core.domain.LibraryItem>())
    override fun observeInProgressItems(libraryId: String) = kotlinx.coroutines.flow.flowOf(emptyList<com.riffle.core.domain.LibraryItem>())
    override fun observeFinishedItems(libraryId: String) = kotlinx.coroutines.flow.flowOf(emptyList<com.riffle.core.domain.LibraryItem>())
    override fun observeRecentlyAddedItems(libraryId: String) = kotlinx.coroutines.flow.flowOf(emptyList<com.riffle.core.domain.LibraryItem>())
    override fun observeAllBooks(libraryId: String) = kotlinx.coroutines.flow.flowOf(emptyList<com.riffle.core.domain.LibraryItem>())
    override fun observeSeries(libraryId: String) = kotlinx.coroutines.flow.flowOf(emptyList<com.riffle.core.domain.Series>())
    override fun observeCollections(libraryId: String) = kotlinx.coroutines.flow.flowOf(emptyList<com.riffle.core.domain.Collection>())
    override fun observeSeriesItems(seriesId: String) = kotlinx.coroutines.flow.flowOf(emptyList<com.riffle.core.domain.LibraryItem>())
    override fun observeCollectionItems(collectionId: String) = kotlinx.coroutines.flow.flowOf(emptyList<com.riffle.core.domain.LibraryItem>())
    override suspend fun getItem(itemId: String): com.riffle.core.domain.LibraryItem? = null
    override suspend fun markItemOpened(itemId: String) {}
    override suspend fun updateReadingProgress(itemId: String, progress: Float) {}
    override suspend fun refreshLibraries(): com.riffle.core.domain.LibraryRefreshResult = com.riffle.core.domain.LibraryRefreshResult.Success
    override suspend fun refreshLibraryItems(libraryId: String): com.riffle.core.domain.LibraryRefreshResult = com.riffle.core.domain.LibraryRefreshResult.Success
    override suspend fun refreshSeries(libraryId: String): com.riffle.core.domain.LibraryRefreshResult = com.riffle.core.domain.LibraryRefreshResult.Success
}

private open class FakeAbsApi(
    val collectionsByLibrary: Map<String, List<NetworkCollection>> = emptyMap(),
) : AbsLibraryApi {
    val createCalls = mutableListOf<Triple<String, String, String?>>()
    val addCalls = mutableListOf<Pair<String, String>>()
    val removeCalls = mutableListOf<Pair<String, String>>()

    override suspend fun getLibraries(baseUrl: String, token: String, insecureAllowed: Boolean): NetworkLibrariesResult =
        NetworkLibrariesResult.Success(emptyList())

    override suspend fun getLibraryItems(baseUrl: String, libraryId: String, token: String, insecureAllowed: Boolean): NetworkLibraryItemsResult =
        NetworkLibraryItemsResult.Success(emptyList())

    override suspend fun getSeries(baseUrl: String, libraryId: String, token: String, insecureAllowed: Boolean): NetworkSeriesResult =
        NetworkSeriesResult.Success(emptyList())

    override suspend fun getCollections(baseUrl: String, libraryId: String, token: String, insecureAllowed: Boolean): NetworkCollectionResult =
        NetworkCollectionResult.Success(collectionsByLibrary[libraryId].orEmpty())

    override suspend fun createCollection(
        baseUrl: String,
        libraryId: String,
        name: String,
        initialBookId: String?,
        token: String,
        insecureAllowed: Boolean,
    ): NetworkCollectionWriteResult {
        createCalls += Triple(libraryId, name, initialBookId)
        val newCol = NetworkCollection("col-new", libraryId, name, emptyList())
        return NetworkCollectionWriteResult.Success(newCol)
    }

    override suspend fun addBookToCollection(
        baseUrl: String,
        collectionId: String,
        libraryItemId: String,
        token: String,
        insecureAllowed: Boolean,
    ): NetworkCollectionWriteResult {
        addCalls += collectionId to libraryItemId
        return NetworkCollectionWriteResult.Success(null)
    }

    override suspend fun removeBookFromCollection(
        baseUrl: String,
        collectionId: String,
        libraryItemId: String,
        token: String,
        insecureAllowed: Boolean,
    ): NetworkCollectionWriteResult {
        removeCalls += collectionId to libraryItemId
        return NetworkCollectionWriteResult.Success(null)
    }
}
