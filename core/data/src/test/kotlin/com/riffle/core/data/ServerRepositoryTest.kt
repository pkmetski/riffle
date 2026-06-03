package com.riffle.core.data

import com.riffle.core.database.LibraryDao
import com.riffle.core.database.LibraryEntity
import com.riffle.core.database.ServerDao
import com.riffle.core.database.ServerEntity
import com.riffle.core.domain.AuthenticateResult
import com.riffle.core.domain.CommitServerResult
import com.riffle.core.domain.InsecureConnectionType
import com.riffle.core.domain.Library
import com.riffle.core.domain.LibraryVisibilityPreferencesStore
import com.riffle.core.domain.PendingServer
import com.riffle.core.domain.ServerType
import com.riffle.core.domain.ServerUrl
import com.riffle.core.domain.TokenStorage
import com.riffle.core.network.AbsApi
import com.riffle.core.network.AbsLibraryApi
import com.riffle.core.network.AbsServerInfoApi
import com.riffle.core.network.NetworkCollectionResult
import com.riffle.core.network.NetworkLibrariesResult
import com.riffle.core.network.NetworkLibrary
import com.riffle.core.network.NetworkLibraryItemsResult
import com.riffle.core.network.NetworkLoginResult
import com.riffle.core.network.NetworkSeriesResult
import com.riffle.core.network.NetworkStorytellerLoginResult
import com.riffle.core.network.StorytellerApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ServerRepositoryTest {

    private val fakeServerInfoApi = object : AbsServerInfoApi {
        override suspend fun getServerInfo(baseUrl: String, token: String, insecureAllowed: Boolean): String? = null
    }

    private fun fakeTokenStorage() = object : TokenStorage {
        val tokens = mutableMapOf<String, String>()
        override suspend fun saveToken(serverId: String, token: String) { tokens[serverId] = token }
        override suspend fun getToken(serverId: String) = tokens[serverId]
        override suspend fun deleteToken(serverId: String) { tokens.remove(serverId) }
    }

    private class FakeServerDao(initial: List<ServerEntity>) : ServerDao {
        val store = initial.toMutableList()
        override fun observeAll() = flowOf(store.toList())
        override suspend fun getActive() = store.firstOrNull { it.isActive }
        override suspend fun getById(id: String): ServerEntity? = store.firstOrNull { it.id == id }
        override suspend fun upsert(server: ServerEntity) {
            store.removeAll { it.id == server.id }
            store.add(server)
        }
        override suspend fun clearActiveFlag() { store.replaceAll { it.copy(isActive = false) } }
        override suspend fun setActive(id: String) { store.replaceAll { if (it.id == id) it.copy(isActive = true) else it } }
        override suspend fun setActiveAtomic(id: String) { clearActiveFlag(); setActive(id) }
        override suspend fun upsertAsFirstIfNoActive(server: ServerEntity): ServerEntity {
            val toInsert = server.copy(isActive = getActive() == null)
            upsert(toInsert)
            return toInsert
        }
        override suspend fun deleteById(id: String) { store.removeAll { it.id == id } }
        fun allCount(): Int = store.size
    }

    private fun fakeDao(vararg initial: ServerEntity): FakeServerDao = FakeServerDao(initial.toList())

    private fun fakeLibraryDao() = object : LibraryDao {
        val rows = mutableMapOf<String, MutableList<LibraryEntity>>()
        override suspend fun replaceAllForServer(serverId: String, libraries: List<LibraryEntity>) {
            rows[serverId] = libraries.toMutableList()
        }
        override suspend fun upsertAll(libraries: List<LibraryEntity>) {
            libraries.forEach { rows.getOrPut(it.serverId) { mutableListOf() }.add(it) }
        }
        override fun observeByServerId(serverId: String): Flow<List<LibraryEntity>> =
            flowOf(rows[serverId].orEmpty().toList())
        override suspend fun libraryIdsForServer(serverId: String): List<String> =
            rows[serverId].orEmpty().map { it.id }
        override suspend fun getById(libraryId: String): LibraryEntity? =
            rows.values.flatten().firstOrNull { it.id == libraryId }
        override suspend fun deleteByServerId(serverId: String) { rows.remove(serverId) }
        override suspend fun setUnsupported(libraryId: String, isUnsupported: Boolean) {
            rows.values.forEach { list ->
                list.replaceAll { if (it.id == libraryId) it.copy(isUnsupported = isUnsupported) else it }
            }
        }
        fun allEntities(): List<LibraryEntity> = rows.values.flatten()
    }

    private fun fakeVisibilityStore() = object : LibraryVisibilityPreferencesStore {
        val hidden = mutableMapOf<String, MutableSet<String>>()
        override fun hiddenLibraryIds(serverId: String): Flow<Set<String>> =
            flowOf(hidden[serverId].orEmpty())
        override suspend fun hideLibrary(serverId: String, libraryId: String) {
            hidden.getOrPut(serverId) { mutableSetOf() }.add(libraryId)
        }
        override suspend fun showLibrary(serverId: String, libraryId: String) {
            hidden[serverId]?.remove(libraryId)
        }
    }

    private fun fakeLibraryItemDao() = object : com.riffle.core.database.LibraryItemDao {
        val deletedLibraryIds = mutableListOf<String>()
        private val rows = mutableMapOf<String, MutableList<com.riffle.core.database.LibraryItemEntity>>()
        fun seed(libraryId: String, items: List<com.riffle.core.database.LibraryItemEntity>) {
            rows[libraryId] = items.toMutableList()
        }
        fun itemsFor(libraryId: String): List<com.riffle.core.database.LibraryItemEntity> = rows[libraryId].orEmpty()
        override fun observeByLibraryId(libraryId: String) =
            flowOf(rows[libraryId].orEmpty().toList())
        override fun observeUngroupedByLibraryId(libraryId: String) =
            flowOf(rows[libraryId].orEmpty().toList())
        override fun observeInProgress(libraryId: String) = flowOf(emptyList<com.riffle.core.database.LibraryItemEntity>())
        override fun observeFinished(libraryId: String) = flowOf(emptyList<com.riffle.core.database.LibraryItemEntity>())
        override fun observeRecentlyAdded(libraryId: String) = flowOf(emptyList<com.riffle.core.database.LibraryItemEntity>())
        override fun observeAllBooks(libraryId: String) = flowOf(emptyList<com.riffle.core.database.LibraryItemEntity>())
        override suspend fun getById(serverId: String, itemId: String) = rows.values.flatten().firstOrNull { it.id == itemId }
        override suspend fun findServerIdForItem(itemId: String): String? = rows.values.flatten().firstOrNull { it.id == itemId }?.serverId
        override suspend fun upsertAll(items: List<com.riffle.core.database.LibraryItemEntity>) {
            items.groupBy { it.libraryId }.forEach { (libraryId, entities) ->
                rows.getOrPut(libraryId) { mutableListOf() }.addAll(entities)
            }
        }
        override suspend fun deleteByLibraryId(libraryId: String) {
            deletedLibraryIds += libraryId
            rows.remove(libraryId)
        }
        override suspend fun updateLastOpenedAt(serverId: String, itemId: String, timestamp: Long) {}
        override suspend fun getLastOpenedAtMap(libraryId: String) = emptyList<com.riffle.core.database.LastOpenedAtRow>()
        override suspend fun getReadingProgressMap(libraryId: String) = emptyList<com.riffle.core.database.ReadingProgressRow>()
        override suspend fun updateReadingProgress(serverId: String, itemId: String, progress: Float) {}
        override suspend fun listMatchableByServerType(serverType: String) = emptyList<com.riffle.core.database.MatchableItemRow>()
    }

    private val storytellerApiNotCalled = StorytellerApi { _, _, _, _ -> error("should not be called") }
    private fun storytellerApiReturning(result: NetworkStorytellerLoginResult) = StorytellerApi { _, _, _, _ -> result }

    private fun libsApiReturning(result: NetworkLibrariesResult) = object : AbsLibraryApi {
        override suspend fun getLibraries(baseUrl: String, token: String, insecureAllowed: Boolean) = result
        override suspend fun getLibraryItems(baseUrl: String, libraryId: String, token: String, insecureAllowed: Boolean): NetworkLibraryItemsResult =
            throw NotImplementedError()
        override suspend fun getSeries(baseUrl: String, libraryId: String, token: String, insecureAllowed: Boolean): NetworkSeriesResult =
            throw NotImplementedError()
        override suspend fun getCollections(baseUrl: String, libraryId: String, token: String, insecureAllowed: Boolean): NetworkCollectionResult =
            throw NotImplementedError()
    }

    private val libsApiNotCalled = object : AbsLibraryApi {
        override suspend fun getLibraries(baseUrl: String, token: String, insecureAllowed: Boolean): NetworkLibrariesResult =
            error("should not be called")
        override suspend fun getLibraryItems(baseUrl: String, libraryId: String, token: String, insecureAllowed: Boolean): NetworkLibraryItemsResult =
            error("should not be called")
        override suspend fun getSeries(baseUrl: String, libraryId: String, token: String, insecureAllowed: Boolean): NetworkSeriesResult =
            error("should not be called")
        override suspend fun getCollections(baseUrl: String, libraryId: String, token: String, insecureAllowed: Boolean): NetworkCollectionResult =
            error("should not be called")
    }

    @Test
    fun `authenticate success returns PendingServer with libraries and persists nothing`() = runTest {
        val dao = fakeDao()
        val tokens = fakeTokenStorage()
        val libDao = fakeLibraryDao()
        val visibility = fakeVisibilityStore()
        val absApi = AbsApi { _, _, _, _ -> NetworkLoginResult.Success("uid-1", "tok-xyz", "admin") }
        val libsApi = libsApiReturning(
            NetworkLibrariesResult.Success(
                listOf(
                    NetworkLibrary(id = "lib-1", name = "Books", mediaType = "book", audiobooksOnly = false),
                    NetworkLibrary(id = "lib-2", name = "Audiobooks", mediaType = "book", audiobooksOnly = false),
                    NetworkLibrary(id = "lib-3", name = "Podcasts", mediaType = "podcast", audiobooksOnly = false),
                )
            )
        )
        val repo = ServerRepositoryImpl(dao, tokens, absApi, storytellerApiNotCalled, fakeServerInfoApi, libsApi, libDao, fakeLibraryItemDao(), visibility)
        val url = ServerUrl.parse("https://abs.example.com")!!

        val result = repo.authenticate(url, "admin", "pass", insecureAllowed = false)

        assertTrue(result is AuthenticateResult.Success)
        val pending = (result as AuthenticateResult.Success).pending
        assertEquals("tok-xyz", pending.token)
        assertEquals(listOf("lib-1", "lib-2"), pending.libraries.map { it.id }) // podcast filtered out
        assertEquals(0, dao.allCount())
        assertNull(tokens.getToken("any"))
        assertTrue(libDao.allEntities().isEmpty())
    }

    @Test
    fun `authenticate wrong credentials surfaces message and persists nothing`() = runTest {
        val dao = fakeDao(); val tokens = fakeTokenStorage()
        val libDao = fakeLibraryDao(); val visibility = fakeVisibilityStore()
        val absApi = AbsApi { _, _, _, _ -> NetworkLoginResult.WrongCredentials("nope") }
        val repo = ServerRepositoryImpl(dao, tokens, absApi, storytellerApiNotCalled, fakeServerInfoApi, libsApiNotCalled, libDao, fakeLibraryItemDao(), visibility)

        val result = repo.authenticate(ServerUrl.parse("https://x")!!, "u", "p", false)

        assertTrue(result is AuthenticateResult.WrongCredentials)
        assertEquals("nope", (result as AuthenticateResult.WrongCredentials).message)
        assertEquals(0, dao.allCount())
    }

    @Test
    fun `authenticate library fetch failure surfaces LibraryFetchFailed and persists nothing`() = runTest {
        val dao = fakeDao(); val tokens = fakeTokenStorage()
        val libDao = fakeLibraryDao(); val visibility = fakeVisibilityStore()
        val absApi = AbsApi { _, _, _, _ -> NetworkLoginResult.Success("uid", "tok", "u") }
        val cause = RuntimeException("boom")
        val libsApi = libsApiReturning(NetworkLibrariesResult.NetworkError(cause))
        val repo = ServerRepositoryImpl(dao, tokens, absApi, storytellerApiNotCalled, fakeServerInfoApi, libsApi, libDao, fakeLibraryItemDao(), visibility)

        val result = repo.authenticate(ServerUrl.parse("https://x")!!, "u", "p", false)

        assertTrue(result is AuthenticateResult.LibraryFetchFailed)
        assertSame(cause, (result as AuthenticateResult.LibraryFetchFailed).cause)
        assertNull(tokens.getToken("any"))
    }

    @Test
    fun `authenticate returns InsecureConnection when network signals self-signed`() = runTest {
        val dao = fakeDao(); val tokens = fakeTokenStorage()
        val libDao = fakeLibraryDao(); val visibility = fakeVisibilityStore()
        val absApi = AbsApi { _, _, _, _ -> NetworkLoginResult.InsecureConnection(InsecureConnectionType.SELF_SIGNED) }
        val repo = ServerRepositoryImpl(dao, tokens, absApi, storytellerApiNotCalled, fakeServerInfoApi, libsApiNotCalled, libDao, fakeLibraryItemDao(), visibility)

        val result = repo.authenticate(ServerUrl.parse("https://abs.example.com")!!, "admin", "pass", insecureAllowed = false)

        assertTrue(result is AuthenticateResult.InsecureConnection)
    }

    @Test
    fun `commit writes server token library cache and hidden ids together`() = runTest {
        val dao = fakeDao(); val tokens = fakeTokenStorage()
        val libDao = fakeLibraryDao(); val visibility = fakeVisibilityStore()
        val absApi = AbsApi { _, _, _, _ -> error("not called") }
        val repo = ServerRepositoryImpl(dao, tokens, absApi, storytellerApiNotCalled, fakeServerInfoApi, libsApiNotCalled, libDao, fakeLibraryItemDao(), visibility)

        val url = ServerUrl.parse("https://abs.example.com")!!
        val pending = PendingServer(
            url = url,
            username = "admin", userId = "uid-1", token = "tok-xyz",
            insecureConnectionAllowed = false,
            libraries = listOf(
                Library("lib-1", "Books", "book", false),
                Library("lib-2", "Audiobooks", "book", false),
            ),
        )

        val result = repo.commit(pending, hiddenLibraryIds = setOf("lib-2"))

        assertTrue(result is CommitServerResult.Success)
        val server = (result as CommitServerResult.Success).server
        assertEquals(url, server.url)
        assertTrue(server.isActive) // first server becomes active
        assertEquals("tok-xyz", tokens.getToken(server.id))
        assertEquals(2, libDao.allEntities().size)
        assertEquals(setOf("lib-2"), visibility.hidden[server.id])
    }

    @Test
    fun `authenticate STORYTELLER returns PendingServer with synthetic Readaloud library and serverType STORYTELLER`() = runTest {
        val dao = fakeDao(); val tokens = fakeTokenStorage()
        val libDao = fakeLibraryDao(); val visibility = fakeVisibilityStore()
        val absApi = AbsApi { _, _, _, _ -> error("ABS auth must not be called for Storyteller") }
        val storyteller = storytellerApiReturning(NetworkStorytellerLoginResult.Success("tok-st"))
        val repo = ServerRepositoryImpl(dao, tokens, absApi, storyteller, fakeServerInfoApi, libsApiNotCalled, libDao, fakeLibraryItemDao(), visibility)

        val result = repo.authenticate(
            ServerUrl.parse("http://media-server:8001")!!, "plamen", "pw", insecureAllowed = false,
            serverType = ServerType.STORYTELLER,
        )

        assertTrue(result is AuthenticateResult.Success)
        val pending = (result as AuthenticateResult.Success).pending
        assertEquals("tok-st", pending.token)
        assertEquals(ServerType.STORYTELLER, pending.serverType)
        // Storyteller contributes no browsable Library (ADR 0026) — the namespace row is created
        // at commit time, not surfaced as a pending library.
        assertTrue(pending.libraries.isEmpty())
        assertEquals(0, dao.allCount())
    }

    @Test
    fun `authenticate STORYTELLER wrong credentials surfaces WrongCredentials`() = runTest {
        val dao = fakeDao(); val tokens = fakeTokenStorage()
        val libDao = fakeLibraryDao(); val visibility = fakeVisibilityStore()
        val absApi = AbsApi { _, _, _, _ -> error("ABS auth must not be called for Storyteller") }
        val storyteller = storytellerApiReturning(NetworkStorytellerLoginResult.WrongCredentials("Incorrect username or password"))
        val repo = ServerRepositoryImpl(dao, tokens, absApi, storyteller, fakeServerInfoApi, libsApiNotCalled, libDao, fakeLibraryItemDao(), visibility)

        val result = repo.authenticate(
            ServerUrl.parse("http://media-server:8001")!!, "plamen", "wrong", insecureAllowed = false,
            serverType = ServerType.STORYTELLER,
        )

        assertTrue(result is AuthenticateResult.WrongCredentials)
    }

    @Test
    fun `two Storyteller servers commit independently, each with their own Readaloud library row`() = runTest {
        val dao = fakeDao(); val tokens = fakeTokenStorage()
        val libDao = fakeLibraryDao(); val visibility = fakeVisibilityStore()
        val absApi = AbsApi { _, _, _, _ -> error("not called") }
        val storyteller = storytellerApiReturning(NetworkStorytellerLoginResult.Success("tok-st"))
        val repo = ServerRepositoryImpl(dao, tokens, absApi, storyteller, fakeServerInfoApi, libsApiNotCalled, libDao, fakeLibraryItemDao(), visibility)

        val pendingA = PendingServer(
            url = ServerUrl.parse("http://media-server:8001")!!,
            username = "plamen", userId = "", token = "tok-A",
            insecureConnectionAllowed = false,
            libraries = emptyList(),
            serverType = ServerType.STORYTELLER,
        )
        val pendingB = PendingServer(
            url = ServerUrl.parse("https://readalouds.example.com")!!,
            username = "plamen", userId = "", token = "tok-B",
            insecureConnectionAllowed = false,
            libraries = emptyList(),
            serverType = ServerType.STORYTELLER,
        )

        val resultA = repo.commit(pendingA, hiddenLibraryIds = emptySet())
        val resultB = repo.commit(pendingB, hiddenLibraryIds = emptySet())

        assertTrue(resultA is CommitServerResult.Success)
        assertTrue(resultB is CommitServerResult.Success)
        val serverA = (resultA as CommitServerResult.Success).server
        val serverB = (resultB as CommitServerResult.Success).server

        assertEquals(2, dao.allCount())
        assertEquals(ServerType.STORYTELLER, serverA.serverType)
        assertEquals(ServerType.STORYTELLER, serverB.serverType)
        // Each server gets its own Readaloud library row with a distinct server-scoped id —
        // the disambiguation requested in #34's acceptance criterion. The library name itself
        // remains the working "Readalouds" label; the active-server context (drawer header /
        // Server Switcher) surfaces which server you're viewing.
        val libs = libDao.allEntities()
        assertEquals(2, libs.size)
        val libA = libs.single { it.serverId == serverA.id }
        val libB = libs.single { it.serverId == serverB.id }
        assertEquals(ServerRepositoryImpl.readaloudLibraryId(serverA.id), libA.id)
        assertEquals(ServerRepositoryImpl.readaloudLibraryId(serverB.id), libB.id)
        assertTrue("Readaloud library ids must be distinct across servers", libA.id != libB.id)
        assertEquals("readaloud", libA.mediaType)
        assertEquals("readaloud", libB.mediaType)
    }

    @Test
    fun `commit Storyteller pending materialises Readaloud library with server-scoped id`() = runTest {
        val dao = fakeDao(); val tokens = fakeTokenStorage()
        val libDao = fakeLibraryDao(); val visibility = fakeVisibilityStore()
        val absApi = AbsApi { _, _, _, _ -> error("not called") }
        val repo = ServerRepositoryImpl(dao, tokens, absApi, storytellerApiNotCalled, fakeServerInfoApi, libsApiNotCalled, libDao, fakeLibraryItemDao(), visibility)

        val pending = PendingServer(
            url = ServerUrl.parse("http://media-server:8001")!!,
            username = "plamen", userId = "", token = "tok-st",
            insecureConnectionAllowed = false,
            libraries = emptyList(),
            serverType = ServerType.STORYTELLER,
        )

        val result = repo.commit(pending, hiddenLibraryIds = emptySet())

        assertTrue(result is CommitServerResult.Success)
        val server = (result as CommitServerResult.Success).server
        val lib = libDao.allEntities().single()
        assertEquals(ServerRepositoryImpl.readaloudLibraryId(server.id), lib.id)
        assertEquals("readaloud", lib.mediaType)
        assertEquals(server.id, lib.serverId)
    }

    @Test
    fun `commit Storyteller server is never marked active even when no server is active`() = runTest {
        val dao = fakeDao(); val tokens = fakeTokenStorage()
        val libDao = fakeLibraryDao(); val visibility = fakeVisibilityStore()
        val absApi = AbsApi { _, _, _, _ -> error("not called") }
        val repo = ServerRepositoryImpl(dao, tokens, absApi, storytellerApiNotCalled, fakeServerInfoApi, libsApiNotCalled, libDao, fakeLibraryItemDao(), visibility)

        val pending = PendingServer(
            url = ServerUrl.parse("http://media-server:8001")!!,
            username = "plamen", userId = "", token = "tok-st",
            insecureConnectionAllowed = false,
            libraries = emptyList(),
            serverType = ServerType.STORYTELLER,
        )

        val result = repo.commit(pending, hiddenLibraryIds = emptySet())

        assertTrue(result is CommitServerResult.Success)
        // Storyteller is a Settings-only readaloud backend (ADR 0026) — it must never become the
        // active browsable Server, even when it is the first server added.
        assertFalse((result as CommitServerResult.Success).server.isActive)
        assertEquals(null, dao.getActive())
    }

    @Test
    fun `commit persists serverType STORYTELLER and round-trips it`() = runTest {
        val dao = fakeDao(); val tokens = fakeTokenStorage()
        val libDao = fakeLibraryDao(); val visibility = fakeVisibilityStore()
        val absApi = AbsApi { _, _, _, _ -> error("not called") }
        val repo = ServerRepositoryImpl(dao, tokens, absApi, storytellerApiNotCalled, fakeServerInfoApi, libsApiNotCalled, libDao, fakeLibraryItemDao(), visibility)

        val pending = PendingServer(
            url = ServerUrl.parse("http://media-server:8001")!!,
            username = "plamen", userId = "uid-1", token = "tok-st",
            insecureConnectionAllowed = false,
            libraries = emptyList(),
            serverType = com.riffle.core.domain.ServerType.STORYTELLER,
        )

        val result = repo.commit(pending, hiddenLibraryIds = emptySet())

        assertTrue(result is CommitServerResult.Success)
        val server = (result as CommitServerResult.Success).server
        assertEquals(com.riffle.core.domain.ServerType.STORYTELLER, server.serverType)
    }

    @Test
    fun `remove deletes server entity and token`() = runTest {
        val entity = ServerEntity("srv-1", "https://abs.example.com", true, false, username = "")
        val dao = fakeDao(entity)
        val tokens = fakeTokenStorage()
        tokens.tokens["srv-1"] = "tok"
        val absApi = AbsApi { _, _, _, _ -> NetworkLoginResult.WrongCredentials("") }
        val repo = ServerRepositoryImpl(
            dao, tokens, absApi, storytellerApiNotCalled, fakeServerInfoApi, libsApiNotCalled, fakeLibraryDao(), fakeLibraryItemDao(), fakeVisibilityStore()
        )
        repo.remove("srv-1")
        assertTrue("token not deleted", tokens.tokens.isEmpty())
        assertNull("entity not deleted from store", dao.getActive())
    }

    @Test
    fun `remove cascades libraries and library_items for a Storyteller server`() = runTest {
        val entity = ServerEntity("st-1", "http://media-server:8001", true, false, username = "plamen", serverType = "STORYTELLER")
        val dao = fakeDao(entity)
        val tokens = fakeTokenStorage()
        tokens.tokens["st-1"] = "tok-st"
        val libDao = fakeLibraryDao()
        val libraryId = ServerRepositoryImpl.readaloudLibraryId("st-1")
        libDao.rows["st-1"] = mutableListOf(LibraryEntity(libraryId, "Readalouds", "readaloud", "st-1"))
        val itemDao = fakeLibraryItemDao()
        itemDao.seed(libraryId, listOf(
            com.riffle.core.database.LibraryItemEntity("st-1", "1385738337074647", libraryId, "The Martian", "Andy Weir", null, 0f),
            com.riffle.core.database.LibraryItemEntity("st-1", "99", libraryId, "Dune", "Frank Herbert", null, 0f),
        ))
        val absApi = AbsApi { _, _, _, _ -> error("not called") }
        val repo = ServerRepositoryImpl(
            dao, tokens, absApi, storytellerApiNotCalled, fakeServerInfoApi, libsApiNotCalled, libDao, itemDao, fakeVisibilityStore()
        )

        repo.remove("st-1")

        assertEquals("server entity not deleted", 0, dao.allCount())
        assertTrue("token not deleted", tokens.tokens.isEmpty())
        assertTrue("library rows not cleared", libDao.allEntities().isEmpty())
        assertTrue("library_items not deleted via deleteByLibraryId", itemDao.deletedLibraryIds.contains(libraryId))
        assertTrue("library items remain after removal", itemDao.itemsFor(libraryId).isEmpty())
    }

    @Test
    fun `remove cascades libraries and library_items for an ABS server`() = runTest {
        val entity = ServerEntity("abs-1", "https://abs.example.com", true, false, username = "u")
        val dao = fakeDao(entity)
        val tokens = fakeTokenStorage()
        tokens.tokens["abs-1"] = "tok"
        val libDao = fakeLibraryDao()
        libDao.rows["abs-1"] = mutableListOf(
            LibraryEntity("lib-1", "Books", "book", "abs-1"),
            LibraryEntity("lib-2", "Audiobooks", "book", "abs-1"),
        )
        val itemDao = fakeLibraryItemDao()
        itemDao.seed("lib-1", listOf(com.riffle.core.database.LibraryItemEntity("s1", "i-1", "lib-1", "Dune", "Herbert", null, 0f)))
        itemDao.seed("lib-2", listOf(com.riffle.core.database.LibraryItemEntity("s1", "i-2", "lib-2", "Foundation", "Asimov", null, 0f)))
        val absApi = AbsApi { _, _, _, _ -> error("not called") }
        val repo = ServerRepositoryImpl(
            dao, tokens, absApi, storytellerApiNotCalled, fakeServerInfoApi, libsApiNotCalled, libDao, itemDao, fakeVisibilityStore()
        )

        repo.remove("abs-1")

        assertEquals(0, dao.allCount())
        assertTrue(libDao.allEntities().isEmpty())
        assertEquals(setOf("lib-1", "lib-2"), itemDao.deletedLibraryIds.toSet())
    }

    @Test
    fun `setActive changes active server`() = runTest {
        val e1 = ServerEntity("s1", "https://one.example.com", true, false, username = "")
        val e2 = ServerEntity("s2", "https://two.example.com", false, false, username = "")
        val dao = fakeDao(e1, e2)
        val absApi = AbsApi { _, _, _, _ -> NetworkLoginResult.WrongCredentials("") }
        val repo = ServerRepositoryImpl(
            dao, fakeTokenStorage(), absApi, storytellerApiNotCalled, fakeServerInfoApi, libsApiNotCalled, fakeLibraryDao(), fakeLibraryItemDao(), fakeVisibilityStore()
        )
        repo.setActive("s2")
        assertEquals("s2", dao.getActive()?.id)
    }
}
