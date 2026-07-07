package com.riffle.core.data

import com.riffle.core.network.NetworkResult

import com.riffle.core.domain.AuthenticateResult
import com.riffle.core.domain.CommitSourceResult
import com.riffle.core.domain.EbookFormat
import com.riffle.core.domain.PendingSource
import com.riffle.core.domain.Source
import com.riffle.core.domain.SourceRepository
import com.riffle.core.domain.SourceUrl
import com.riffle.core.domain.TokenStorage
import com.riffle.core.network.AbsLibraryApi
import com.riffle.core.network.NetworkLibraryItem
import com.riffle.core.network.NetworkPlaylist
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class ToReadRepositoryTest {

    private val activeServer = Source(
        id = "s1",
        url = SourceUrl.parse("http://abs.local")!!,
        isActive = true,
        insecureConnectionAllowed = false,
        username = "u",
    )

    private fun makeRepo(
        api: AbsLibraryApi = FakeAbsApi(),
        sourceRepository: SourceRepository = FakeSourceRepository(activeServer),
        tokenStorage: TokenStorage = FakeTokenStorage(mutableMapOf("s1" to "tok")),
    ) = ToReadRepositoryImpl(api, sourceRepository, tokenStorage)

    // ── refresh + observeToReadItemIds ────────────────────────────────────────

    @Test
    fun `refresh populates cache from source`() = runTest {
        val api = FakeAbsApi(
            playlistsByLibrary = mapOf("lib-1" to listOf(playlist("pl-A", "To Read", listOf("item-1", "item-2")))),
        )
        val repo = makeRepo(api)
        assertTrue(repo.refresh("lib-1"))
        assertEquals(setOf("item-1", "item-2"), repo.observeToReadItemIds("lib-1").first())
    }

    @Test
    fun `refresh populates empty when no To Read playlist exists`() = runTest {
        val api = FakeAbsApi(playlistsByLibrary = mapOf("lib-1" to emptyList()))
        val repo = makeRepo(api)
        assertTrue(repo.refresh("lib-1"))
        assertEquals(emptySet<String>(), repo.observeToReadItemIds("lib-1").first())
    }

    @Test
    fun `refresh returns false on network error and leaves cache untouched`() = runTest {
        val api = object : FakeAbsApi() {
            override suspend fun getPlaylists(baseUrl: String, libraryId: String, token: String, insecureAllowed: Boolean): NetworkResult<List<NetworkPlaylist>> =
                NetworkResult.Offline(IOException("HTTP 500"))
        }
        val repo = makeRepo(api)
        assertFalse(repo.refresh("lib-1"))
        assertEquals(emptySet<String>(), repo.observeToReadItemIds("lib-1").first())
    }

    // ── isInToRead ────────────────────────────────────────────────────────────

    @Test
    fun `isInToRead reads from cache after refresh`() = runTest {
        val api = FakeAbsApi(
            playlistsByLibrary = mapOf("lib-1" to listOf(playlist("pl-A", "To Read", listOf("item-1")))),
        )
        val repo = makeRepo(api)
        repo.refresh("lib-1")
        assertTrue(repo.isInToRead("item-1", "lib-1"))
        assertFalse(repo.isInToRead("item-9", "lib-1"))
    }

    @Test
    fun `isInToRead returns false before any refresh`() = runTest {
        val api = FakeAbsApi(
            playlistsByLibrary = mapOf("lib-1" to listOf(playlist("pl-A", "To Read", listOf("item-1")))),
        )
        assertFalse(makeRepo(api).isInToRead("item-1", "lib-1"))
    }

    // ── addToToRead ───────────────────────────────────────────────────────────

    @Test
    fun `addToToRead appends to existing playlist and updates cache optimistically`() = runTest {
        val api = FakeAbsApi(
            playlistsByLibrary = mapOf("lib-1" to listOf(playlist("pl-A", "To Read", emptyList()))),
        )
        val repo = makeRepo(api)
        repo.refresh("lib-1")
        assertTrue(repo.addToToRead("item-1", "lib-1"))
        assertTrue(api.createCalls.isEmpty())
        assertEquals(listOf("pl-A" to "item-1"), api.addCalls)
        assertEquals(setOf("item-1"), repo.observeToReadItemIds("lib-1").first())
    }

    @Test
    fun `addToToRead creates playlist when cache is empty + no playlist on source`() = runTest {
        val api = FakeAbsApi(playlistsByLibrary = mapOf("lib-1" to emptyList()))
        val repo = makeRepo(api)
        repo.refresh("lib-1")
        assertTrue(repo.addToToRead("item-1", "lib-1"))
        assertEquals(listOf(Triple("lib-1", "To Read", "item-1")), api.createCalls)
        assertTrue(api.addCalls.isEmpty())
        assertEquals(setOf("item-1"), repo.observeToReadItemIds("lib-1").first())
    }

    @Test
    fun `addToToRead returns false when no active source`() = runTest {
        val repo = makeRepo(
            sourceRepository = FakeSourceRepository(activeServer = null),
            tokenStorage = FakeTokenStorage(mutableMapOf()),
        )
        assertFalse(repo.addToToRead("item-1", "lib-1"))
    }

    @Test
    fun `addToToRead reverts cache when add fails`() = runTest {
        val api = object : FakeAbsApi(
            playlistsByLibrary = mapOf("lib-1" to listOf(playlist("pl-A", "To Read", emptyList()))),
        ) {
            override suspend fun addBookToPlaylist(
                baseUrl: String, playlistId: String, libraryItemId: String,
                token: String, insecureAllowed: Boolean,
            ): NetworkResult<NetworkPlaylist?> = NetworkResult.Offline(IOException("HTTP 500"))
        }
        val repo = makeRepo(api)
        repo.refresh("lib-1")
        assertFalse(repo.addToToRead("item-1", "lib-1"))
        assertEquals(emptySet<String>(), repo.observeToReadItemIds("lib-1").first())
    }

    @Test
    fun `addToToRead reverts cache when create fails`() = runTest {
        val api = object : FakeAbsApi(playlistsByLibrary = mapOf("lib-1" to emptyList())) {
            override suspend fun createPlaylist(
                baseUrl: String, libraryId: String, name: String, initialBookId: String?,
                token: String, insecureAllowed: Boolean,
            ): NetworkResult<NetworkPlaylist?> = NetworkResult.Offline(IOException("HTTP 500"))
        }
        val repo = makeRepo(api)
        repo.refresh("lib-1")
        assertFalse(repo.addToToRead("item-1", "lib-1"))
        assertEquals(emptySet<String>(), repo.observeToReadItemIds("lib-1").first())
    }

    // ── removeFromToRead ──────────────────────────────────────────────────────

    @Test
    fun `removeFromToRead calls DELETE and updates cache`() = runTest {
        val api = FakeAbsApi(
            playlistsByLibrary = mapOf("lib-1" to listOf(playlist("pl-A", "To Read", listOf("item-1")))),
        )
        val repo = makeRepo(api)
        repo.refresh("lib-1")
        assertTrue(repo.removeFromToRead("item-1", "lib-1"))
        assertEquals(listOf("pl-A" to "item-1"), api.removeCalls)
        assertEquals(emptySet<String>(), repo.observeToReadItemIds("lib-1").first())
    }

    @Test
    fun `removeFromToRead clears cached playlistId when last item is removed`() = runTest {
        // ABS auto-deletes empty playlists source-side, so the cached id must be invalidated
        // or the next add will POST to a dead playlist id.
        val api = FakeAbsApi(
            playlistsByLibrary = mapOf("lib-1" to listOf(playlist("pl-A", "To Read", listOf("item-1")))),
        )
        val repo = makeRepo(api)
        repo.refresh("lib-1")
        assertTrue(repo.removeFromToRead("item-1", "lib-1"))
        // Next add must create a new playlist, not POST to the dead pl-A.
        assertTrue(repo.addToToRead("item-2", "lib-1"))
        assertEquals(listOf(Triple("lib-1", "To Read", "item-2")), api.createCalls)
        assertTrue(api.addCalls.isEmpty())
    }

    @Test
    fun `removeFromToRead returns true and makes no call when cache empty`() = runTest {
        val api = FakeAbsApi(playlistsByLibrary = mapOf("lib-1" to emptyList()))
        val repo = makeRepo(api)
        repo.refresh("lib-1")
        assertTrue(repo.removeFromToRead("item-1", "lib-1"))
        assertTrue(api.removeCalls.isEmpty())
    }

    @Test
    fun `removeFromToRead reverts cache when DELETE fails`() = runTest {
        val api = object : FakeAbsApi(
            playlistsByLibrary = mapOf("lib-1" to listOf(playlist("pl-A", "To Read", listOf("item-1")))),
        ) {
            override suspend fun removeBookFromPlaylist(
                baseUrl: String, playlistId: String, libraryItemId: String,
                token: String, insecureAllowed: Boolean,
            ): NetworkResult<NetworkPlaylist?> = NetworkResult.Offline(IOException("HTTP 500"))
        }
        val repo = makeRepo(api)
        repo.refresh("lib-1")
        assertFalse(repo.removeFromToRead("item-1", "lib-1"))
        assertEquals(setOf("item-1"), repo.observeToReadItemIds("lib-1").first())
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private fun playlist(id: String, name: String, itemIds: List<String>) = NetworkPlaylist(
        id = id, libraryId = "lib-1", name = name,
        items = itemIds.map { NetworkLibraryItem(
            id = it, libraryId = "lib-1", title = "T", author = "A",
            readingProgress = 0f, ebookFormat = EbookFormat.Epub,
        ) },
        bookIds = itemIds.toSet(),
    )
}

private class FakeSourceRepository(private val activeServer: Source?) : SourceRepository {
    override fun observeAll() = MutableStateFlow(listOfNotNull(activeServer))
    override suspend fun getActive(): Source? = activeServer
    override suspend fun authenticate(url: SourceUrl, username: String, password: String, insecureAllowed: Boolean, serverType: com.riffle.core.domain.ServerType): AuthenticateResult =
        AuthenticateResult.NetworkError(IOException())
    override suspend fun commit(pending: PendingSource, hiddenLibraryIds: Set<String>): CommitSourceResult =
        CommitSourceResult.Failure(IOException())
    override suspend fun setActive(sourceId: String) {}
    override suspend fun remove(sourceId: String) {}
    override suspend fun getSourceVersion(sourceId: String): String? = null
}

private class FakeTokenStorage(private val tokens: MutableMap<String, String>) : TokenStorage {
    override suspend fun saveToken(sourceId: String, token: String) { tokens[sourceId] = token }
    override suspend fun getToken(sourceId: String): String? = tokens[sourceId]
    override suspend fun deleteToken(sourceId: String) { tokens.remove(sourceId) }
}

private open class FakeAbsApi(
    val playlistsByLibrary: Map<String, List<NetworkPlaylist>> = emptyMap(),
) : AbsLibraryApi {
    val createCalls = mutableListOf<Triple<String, String, String?>>()
    val addCalls = mutableListOf<Pair<String, String>>()
    val removeCalls = mutableListOf<Pair<String, String>>()

    override suspend fun getLibraries(baseUrl: String, token: String, insecureAllowed: Boolean): NetworkResult<List<com.riffle.core.network.NetworkLibrary>> =
        NetworkResult.Success(emptyList())

    override suspend fun getLibraryItems(baseUrl: String, libraryId: String, token: String, insecureAllowed: Boolean): NetworkResult<List<com.riffle.core.network.NetworkLibraryItem>> =
        NetworkResult.Success(emptyList())

    override suspend fun getSeries(baseUrl: String, libraryId: String, token: String, insecureAllowed: Boolean): NetworkResult<List<com.riffle.core.network.NetworkSeries>> =
        NetworkResult.Success(emptyList())

    override suspend fun getCollections(baseUrl: String, libraryId: String, token: String, insecureAllowed: Boolean): NetworkResult<List<com.riffle.core.network.NetworkCollection>> =
        NetworkResult.Success(emptyList())

    override suspend fun getPlaylists(baseUrl: String, libraryId: String, token: String, insecureAllowed: Boolean): NetworkResult<List<NetworkPlaylist>> =
        NetworkResult.Success(playlistsByLibrary[libraryId].orEmpty())

    override suspend fun createPlaylist(
        baseUrl: String, libraryId: String, name: String, initialBookId: String?,
        token: String, insecureAllowed: Boolean,
    ): NetworkResult<NetworkPlaylist?> {
        createCalls += Triple(libraryId, name, initialBookId)
        return NetworkResult.Success(NetworkPlaylist("pl-new", libraryId, name, emptyList(), emptySet()))
    }

    override suspend fun addBookToPlaylist(
        baseUrl: String, playlistId: String, libraryItemId: String,
        token: String, insecureAllowed: Boolean,
    ): NetworkResult<NetworkPlaylist?> {
        addCalls += playlistId to libraryItemId
        return NetworkResult.Success(null)
    }

    override suspend fun removeBookFromPlaylist(
        baseUrl: String, playlistId: String, libraryItemId: String,
        token: String, insecureAllowed: Boolean,
    ): NetworkResult<NetworkPlaylist?> {
        removeCalls += playlistId to libraryItemId
        return NetworkResult.Success(null)
    }
}
