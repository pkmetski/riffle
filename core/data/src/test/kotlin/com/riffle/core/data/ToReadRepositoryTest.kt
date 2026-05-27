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

    private fun makeRepo(api: AbsLibraryApi): ToReadRepositoryImpl =
        ToReadRepositoryImpl(
            api = api,
            serverRepository = FakeServerRepository(activeServer),
            tokenStorage = FakeTokenStorage(mutableMapOf("s1" to "tok")),
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
        )
        assertFalse(repo.isInToRead("item-1", "lib-1"))
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
