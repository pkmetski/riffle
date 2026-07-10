package com.riffle.core.data

import com.riffle.core.network.NetworkResult

import com.riffle.core.database.LibraryDao
import com.riffle.core.database.LibraryEntity
import com.riffle.core.database.SourceDao
import com.riffle.core.database.SourceEntity
import com.riffle.core.domain.AuthenticateResult
import com.riffle.core.domain.CommitSourceResult
import com.riffle.core.domain.InsecureConnectionType
import com.riffle.core.domain.Library
import com.riffle.core.domain.LibraryVisibilityPreferencesStore
import com.riffle.core.domain.PendingSource
import com.riffle.core.domain.SourceFilesCleaner
import com.riffle.core.domain.ServerType
import com.riffle.core.domain.SourceUrl
import com.riffle.core.domain.TokenStorage
import com.riffle.core.network.AbsApi
import com.riffle.core.network.AbsLibraryApi
import com.riffle.core.network.AbsServerInfoApi
import com.riffle.core.network.NetworkLibrary
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
        override suspend fun getCurrentUserId(baseUrl: String, token: String, insecureAllowed: Boolean): String? = null
    }

    /** ServerInfo API stub that returns a fixed user id (or null) so backfill tests can drive both
     *  the success and failure paths of [SourceRepositoryImpl.ensureAbsUserId]. */
    private class RecordingServerInfoApi(private val userId: String?) : AbsServerInfoApi {
        var getCurrentUserIdCalls = 0
        override suspend fun getServerInfo(baseUrl: String, token: String, insecureAllowed: Boolean): String? = null
        override suspend fun getCurrentUserId(baseUrl: String, token: String, insecureAllowed: Boolean): String? {
            getCurrentUserIdCalls++
            return userId
        }
    }

    private class FakeTokenStorage : TokenStorage {
        val tokens = mutableMapOf<String, String>()
        val passwords = mutableMapOf<String, String>()
        override suspend fun saveToken(sourceId: String, token: String) { tokens[sourceId] = token }
        override suspend fun getToken(sourceId: String) = tokens[sourceId]
        override suspend fun deleteToken(sourceId: String) { tokens.remove(sourceId) }
        override suspend fun savePassword(sourceId: String, password: String) { passwords[sourceId] = password }
        override suspend fun getPassword(sourceId: String) = passwords[sourceId]
        override suspend fun deletePassword(sourceId: String) { passwords.remove(sourceId) }
    }

    private fun fakeTokenStorage() = FakeTokenStorage()

    private class FakeServerDao(initial: List<SourceEntity>) : SourceDao {
        val store = initial.toMutableList()
        override fun observeAll() = flowOf(store.toList())
        override suspend fun getActive() = store.firstOrNull { it.isActive }
        override suspend fun getById(id: String): SourceEntity? = store.firstOrNull { it.id == id }
        override suspend fun getByType(type: String): SourceEntity? = store.firstOrNull { it.type == type }
        override suspend fun upsert(source: SourceEntity) {
            store.removeAll { it.id == source.id }
            store.add(source)
        }
        override suspend fun clearActiveFlag() { store.replaceAll { it.copy(isActive = false) } }
        override suspend fun setActive(id: String) { store.replaceAll { if (it.id == id) it.copy(isActive = true) else it } }
        override suspend fun setActiveAtomic(id: String) { clearActiveFlag(); setActive(id) }
        override suspend fun upsertAsFirstIfNoActive(source: SourceEntity): SourceEntity {
            val toInsert = source.copy(isActive = getActive() == null)
            upsert(toInsert)
            return toInsert
        }
        override suspend fun deleteById(id: String) { store.removeAll { it.id == id } }
        override suspend fun setAbsUserId(id: String, absUserId: String) {
            store.replaceAll { if (it.id == id) it.copy(absUserId = absUserId) else it }
        }
        fun allCount(): Int = store.size
    }

    private fun fakeDao(vararg initial: SourceEntity): FakeServerDao = FakeServerDao(initial.toList())

    private fun fakeLibraryDao() = object : LibraryDao {
        val rows = mutableMapOf<String, MutableList<LibraryEntity>>()
        override suspend fun replaceAllForSource(sourceId: String, libraries: List<LibraryEntity>) {
            rows[sourceId] = libraries.toMutableList()
        }
        override suspend fun upsertAll(libraries: List<LibraryEntity>) {
            libraries.forEach { rows.getOrPut(it.sourceId) { mutableListOf() }.add(it) }
        }
        override fun observeBySourceId(sourceId: String): Flow<List<LibraryEntity>> =
            flowOf(rows[sourceId].orEmpty().toList())
        override suspend fun libraryIdsForSource(sourceId: String): List<String> =
            rows[sourceId].orEmpty().map { it.id }
        override suspend fun getById(sourceId: String, libraryId: String): LibraryEntity? =
            rows[sourceId].orEmpty().firstOrNull { it.id == libraryId }
        override suspend fun deleteBySourceId(sourceId: String) { rows.remove(sourceId) }
        override suspend fun setUnsupported(sourceId: String, libraryId: String, isUnsupported: Boolean) {
            rows[sourceId]?.replaceAll { if (it.id == libraryId) it.copy(isUnsupported = isUnsupported) else it }
        }
        fun allEntities(): List<LibraryEntity> = rows.values.flatten()
    }

    private fun fakeVisibilityStore() = object : LibraryVisibilityPreferencesStore {
        val hidden = mutableMapOf<String, MutableSet<String>>()
        override fun hiddenLibraryIds(sourceId: String): Flow<Set<String>> =
            flowOf(hidden[sourceId].orEmpty())
        override suspend fun hideLibrary(sourceId: String, libraryId: String) {
            hidden.getOrPut(sourceId) { mutableSetOf() }.add(libraryId)
        }
        override suspend fun showLibrary(sourceId: String, libraryId: String) {
            hidden[sourceId]?.remove(libraryId)
        }
    }

    private class RecordingFilesCleaner : SourceFilesCleaner {
        val cleanedServerIds = mutableListOf<String>()
        override suspend fun deleteAllForSource(sourceId: String) { cleanedServerIds += sourceId }
    }

    private fun fakeFilesCleaner() = RecordingFilesCleaner()

    private fun fakeLibraryItemDao() = object : com.riffle.core.database.LibraryItemDao {
        val deletedLibraryIds = mutableListOf<String>()
        private val rows = mutableMapOf<String, MutableList<com.riffle.core.database.LibraryItemEntity>>()
        fun seed(libraryId: String, items: List<com.riffle.core.database.LibraryItemEntity>) {
            rows[libraryId] = items.toMutableList()
        }
        fun itemsFor(libraryId: String): List<com.riffle.core.database.LibraryItemEntity> = rows[libraryId].orEmpty()
        override fun observeByLibraryId(sourceId: String, libraryId: String) =
            flowOf(rows[libraryId].orEmpty().filter { it.sourceId == sourceId }.toList())
        override fun observeUngroupedByLibraryId(sourceId: String, libraryId: String) =
            flowOf(rows[libraryId].orEmpty().filter { it.sourceId == sourceId }.toList())
        override fun observeInProgress(sourceId: String, libraryId: String) = flowOf(emptyList<com.riffle.core.database.LibraryItemEntity>())
        override fun observeFinished(sourceId: String, libraryId: String) = flowOf(emptyList<com.riffle.core.database.LibraryItemEntity>())
        override fun observeRecentlyAdded(sourceId: String, libraryId: String) = flowOf(emptyList<com.riffle.core.database.LibraryItemEntity>())
        override fun observeAllBooks(sourceId: String, libraryId: String) = flowOf(emptyList<com.riffle.core.database.LibraryItemEntity>())
        override suspend fun getById(sourceId: String, itemId: String) = rows.values.flatten().firstOrNull { it.id == itemId }
        override suspend fun listByLibraryId(sourceId: String, libraryId: String) =
            rows[libraryId].orEmpty().filter { it.sourceId == sourceId }.toList()
        override fun observeById(sourceId: String, itemId: String) = flowOf(rows.values.flatten().firstOrNull { it.id == itemId })
        override suspend fun findSourceIdForItem(itemId: String): String? = rows.values.flatten().firstOrNull { it.id == itemId }?.sourceId
        override suspend fun upsertAll(items: List<com.riffle.core.database.LibraryItemEntity>) {
            items.groupBy { it.libraryId }.forEach { (libraryId, entities) ->
                rows.getOrPut(libraryId) { mutableListOf() }.addAll(entities)
            }
        }
        override suspend fun insertOrIgnore(items: List<com.riffle.core.database.LibraryItemEntity>) = Unit
        override suspend fun updateMetadata(metadata: com.riffle.core.database.LibraryItemMetadata) = Unit
        override suspend fun deleteByLibraryId(sourceId: String, libraryId: String) {
            deletedLibraryIds += libraryId
            rows[libraryId]?.removeAll { it.sourceId == sourceId }
            if (rows[libraryId]?.isEmpty() == true) rows.remove(libraryId)
        }
        override suspend fun deleteById(sourceId: String, itemId: String) {
            rows.forEach { (_, v) -> v.removeAll { it.sourceId == sourceId && it.id == itemId } }
        }
        override suspend fun deleteRemovedFromLibrary(sourceId: String, libraryId: String, serverItemIds: List<String>) = Unit
        override suspend fun updateLastOpenedAt(sourceId: String, itemId: String, timestamp: Long) {}
        override suspend fun getLastOpenedAtMap(sourceId: String, libraryId: String) = emptyList<com.riffle.core.database.LastOpenedAtRow>()
        override suspend fun getReadingProgressMap(sourceId: String, libraryId: String) = emptyList<com.riffle.core.database.ReadingProgressRow>()
        override suspend fun updateReadingProgress(sourceId: String, itemId: String, progress: Float) {}
        override suspend fun updateFinishedAt(sourceId: String, itemId: String, finishedAt: Long?) {}
        override suspend fun listMatchableBySourceType(serverType: String) = emptyList<com.riffle.core.database.MatchableItemRow>()
        override fun observeBySource(sourceId: String) = flowOf(emptyList<com.riffle.core.database.LibraryItemEntity>())
    }

    private val storytellerApiNotCalled = StorytellerApi { _, _, _, _ -> error("should not be called") }
    private fun storytellerApiReturning(result: NetworkResult<String>) = StorytellerApi { _, _, _, _ -> result }

    private fun libsApiReturning(result: NetworkResult<List<com.riffle.core.network.NetworkLibrary>>) = object : AbsLibraryApi {
        override suspend fun getLibraries(baseUrl: String, token: String, insecureAllowed: Boolean) = result
        override suspend fun getLibraryItems(baseUrl: String, libraryId: String, token: String, insecureAllowed: Boolean): NetworkResult<List<com.riffle.core.network.NetworkLibraryItem>> =
            throw NotImplementedError()
        override suspend fun getSeries(baseUrl: String, libraryId: String, token: String, insecureAllowed: Boolean): NetworkResult<List<com.riffle.core.network.NetworkSeries>> =
            throw NotImplementedError()
        override suspend fun getCollections(baseUrl: String, libraryId: String, token: String, insecureAllowed: Boolean): NetworkResult<List<com.riffle.core.network.NetworkCollection>> =
            throw NotImplementedError()
    }

    private val libsApiNotCalled = object : AbsLibraryApi {
        override suspend fun getLibraries(baseUrl: String, token: String, insecureAllowed: Boolean): NetworkResult<List<com.riffle.core.network.NetworkLibrary>> =
            error("should not be called")
        override suspend fun getLibraryItems(baseUrl: String, libraryId: String, token: String, insecureAllowed: Boolean): NetworkResult<List<com.riffle.core.network.NetworkLibraryItem>> =
            error("should not be called")
        override suspend fun getSeries(baseUrl: String, libraryId: String, token: String, insecureAllowed: Boolean): NetworkResult<List<com.riffle.core.network.NetworkSeries>> =
            error("should not be called")
        override suspend fun getCollections(baseUrl: String, libraryId: String, token: String, insecureAllowed: Boolean): NetworkResult<List<com.riffle.core.network.NetworkCollection>> =
            error("should not be called")
    }

    @Test
    fun `authenticate success returns PendingSource with libraries and persists nothing`() = runTest {
        val dao = fakeDao()
        val tokens = fakeTokenStorage()
        val libDao = fakeLibraryDao()
        val visibility = fakeVisibilityStore()
        val absApi = AbsApi { _, _, _, _ -> NetworkResult.Success(com.riffle.core.network.NetworkLoginUser("uid-1", "tok-xyz", "admin")) }
        val libsApi = libsApiReturning(
            NetworkResult.Success(
                listOf(
                    NetworkLibrary(id = "lib-1", name = "Books", mediaType = "book", audiobooksOnly = false),
                    NetworkLibrary(id = "lib-2", name = "Audiobooks", mediaType = "book", audiobooksOnly = false),
                    NetworkLibrary(id = "lib-3", name = "Podcasts", mediaType = "podcast", audiobooksOnly = false),
                )
            )
        )
        val repo = SourceRepositoryImpl(dao, tokens, absApi, storytellerApiNotCalled, fakeServerInfoApi, libsApi, libDao, fakeLibraryItemDao(), visibility, fakeFilesCleaner())
        val url = SourceUrl.parse("https://abs.example.com")!!

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
        val absApi = AbsApi { _, _, _, _ -> NetworkResult.Auth }
        val repo = SourceRepositoryImpl(dao, tokens, absApi, storytellerApiNotCalled, fakeServerInfoApi, libsApiNotCalled, libDao, fakeLibraryItemDao(), visibility, fakeFilesCleaner())

        val result = repo.authenticate(SourceUrl.parse("https://x")!!, "u", "p", false)

        assertTrue(result is AuthenticateResult.WrongCredentials)
        // The unified classifier maps 401 → Auth; SourceRepositoryImpl surfaces a fixed user-facing message.
        assertTrue((result as AuthenticateResult.WrongCredentials).message.isNotBlank())
        assertEquals(0, dao.allCount())
    }

    @Test
    fun `authenticate library fetch failure surfaces LibraryFetchFailed and persists nothing`() = runTest {
        val dao = fakeDao(); val tokens = fakeTokenStorage()
        val libDao = fakeLibraryDao(); val visibility = fakeVisibilityStore()
        val absApi = AbsApi { _, _, _, _ -> NetworkResult.Success(com.riffle.core.network.NetworkLoginUser("uid", "tok", "u")) }
        val cause = RuntimeException("boom")
        val libsApi = libsApiReturning(NetworkResult.Offline(cause))
        val repo = SourceRepositoryImpl(dao, tokens, absApi, storytellerApiNotCalled, fakeServerInfoApi, libsApi, libDao, fakeLibraryItemDao(), visibility, fakeFilesCleaner())

        val result = repo.authenticate(SourceUrl.parse("https://x")!!, "u", "p", false)

        assertTrue(result is AuthenticateResult.LibraryFetchFailed)
        assertSame(cause, (result as AuthenticateResult.LibraryFetchFailed).cause)
        assertNull(tokens.getToken("any"))
    }

    @Test
    fun `authenticate returns InsecureConnection when network signals self-signed`() = runTest {
        val dao = fakeDao(); val tokens = fakeTokenStorage()
        val libDao = fakeLibraryDao(); val visibility = fakeVisibilityStore()
        val absApi = AbsApi { _, _, _, _ -> NetworkResult.InsecureConnection(InsecureConnectionType.SELF_SIGNED) }
        val repo = SourceRepositoryImpl(dao, tokens, absApi, storytellerApiNotCalled, fakeServerInfoApi, libsApiNotCalled, libDao, fakeLibraryItemDao(), visibility, fakeFilesCleaner())

        val result = repo.authenticate(SourceUrl.parse("https://abs.example.com")!!, "admin", "pass", insecureAllowed = false)

        assertTrue(result is AuthenticateResult.InsecureConnection)
    }

    @Test
    fun `commit writes source token library cache and hidden ids together`() = runTest {
        val dao = fakeDao(); val tokens = fakeTokenStorage()
        val libDao = fakeLibraryDao(); val visibility = fakeVisibilityStore()
        val absApi = AbsApi { _, _, _, _ -> error("not called") }
        val repo = SourceRepositoryImpl(dao, tokens, absApi, storytellerApiNotCalled, fakeServerInfoApi, libsApiNotCalled, libDao, fakeLibraryItemDao(), visibility, fakeFilesCleaner())

        val url = SourceUrl.parse("https://abs.example.com")!!
        val pending = PendingSource(
            url = url,
            username = "admin", userId = "uid-1", token = "tok-xyz", password = "",
            insecureConnectionAllowed = false,
            libraries = listOf(
                Library("lib-1", "Books", "book", false),
                Library("lib-2", "Audiobooks", "book", false),
            ),
        )

        val result = repo.commit(pending, hiddenLibraryIds = setOf("lib-2"))

        assertTrue(result is CommitSourceResult.Success)
        val source = (result as CommitSourceResult.Success).source
        assertEquals(url, source.url)
        assertTrue(source.isActive) // first source becomes active
        assertEquals("tok-xyz", tokens.getToken(source.id))
        assertEquals(2, libDao.allEntities().size)
        assertEquals(setOf("lib-2"), visibility.hidden[source.id])
    }

    @Test
    fun `authenticate STORYTELLER returns PendingSource with synthetic Readaloud library and serverType STORYTELLER`() = runTest {
        val dao = fakeDao(); val tokens = fakeTokenStorage()
        val libDao = fakeLibraryDao(); val visibility = fakeVisibilityStore()
        val absApi = AbsApi { _, _, _, _ -> error("ABS auth must not be called for Storyteller") }
        val storyteller = storytellerApiReturning(NetworkResult.Success("tok-st"))
        val repo = SourceRepositoryImpl(dao, tokens, absApi, storyteller, fakeServerInfoApi, libsApiNotCalled, libDao, fakeLibraryItemDao(), visibility, fakeFilesCleaner())

        val result = repo.authenticate(
            SourceUrl.parse("http://media-source:8001")!!, "plamen", "pw", insecureAllowed = false,
            serverType = ServerType.STORYTELLER_SERVICE,
        )

        assertTrue(result is AuthenticateResult.Success)
        val pending = (result as AuthenticateResult.Success).pending
        assertEquals("tok-st", pending.token)
        assertEquals(ServerType.STORYTELLER_SERVICE, pending.serverType)
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
        val storyteller = storytellerApiReturning(NetworkResult.Auth)
        val repo = SourceRepositoryImpl(dao, tokens, absApi, storyteller, fakeServerInfoApi, libsApiNotCalled, libDao, fakeLibraryItemDao(), visibility, fakeFilesCleaner())

        val result = repo.authenticate(
            SourceUrl.parse("http://media-source:8001")!!, "plamen", "wrong", insecureAllowed = false,
            serverType = ServerType.STORYTELLER_SERVICE,
        )

        assertTrue(result is AuthenticateResult.WrongCredentials)
    }

    @Test
    fun `two Storyteller services commit independently, each with their own Readaloud library row`() = runTest {
        val dao = fakeDao(); val tokens = fakeTokenStorage()
        val libDao = fakeLibraryDao(); val visibility = fakeVisibilityStore()
        val absApi = AbsApi { _, _, _, _ -> error("not called") }
        val storyteller = storytellerApiReturning(NetworkResult.Success("tok-st"))
        val repo = SourceRepositoryImpl(dao, tokens, absApi, storyteller, fakeServerInfoApi, libsApiNotCalled, libDao, fakeLibraryItemDao(), visibility, fakeFilesCleaner())

        val pendingA = PendingSource(
            url = SourceUrl.parse("http://media-source:8001")!!,
            username = "plamen", userId = "", token = "tok-A", password = "",
            insecureConnectionAllowed = false,
            libraries = emptyList(),
            serverType = ServerType.STORYTELLER_SERVICE,
        )
        val pendingB = PendingSource(
            url = SourceUrl.parse("https://readalouds.example.com")!!,
            username = "plamen", userId = "", token = "tok-B", password = "",
            insecureConnectionAllowed = false,
            libraries = emptyList(),
            serverType = ServerType.STORYTELLER_SERVICE,
        )

        val resultA = repo.commit(pendingA, hiddenLibraryIds = emptySet())
        val resultB = repo.commit(pendingB, hiddenLibraryIds = emptySet())

        assertTrue(resultA is CommitSourceResult.Success)
        assertTrue(resultB is CommitSourceResult.Success)
        val serverA = (resultA as CommitSourceResult.Success).source
        val serverB = (resultB as CommitSourceResult.Success).source

        assertEquals(2, dao.allCount())
        assertEquals(ServerType.STORYTELLER_SERVICE, serverA.serverType)
        assertEquals(ServerType.STORYTELLER_SERVICE, serverB.serverType)
        // Each source gets its own Readaloud library row with a distinct source-scoped id —
        // the disambiguation requested in #34's acceptance criterion. The library name itself
        // remains the working "Readalouds" label; the active-source context (drawer header /
        // Source Switcher) surfaces which source you're viewing.
        val libs = libDao.allEntities()
        assertEquals(2, libs.size)
        val libA = libs.single { it.sourceId == serverA.id }
        val libB = libs.single { it.sourceId == serverB.id }
        assertEquals(SourceRepositoryImpl.readaloudLibraryId(serverA.id), libA.id)
        assertEquals(SourceRepositoryImpl.readaloudLibraryId(serverB.id), libB.id)
        assertTrue("Readaloud library ids must be distinct across servers", libA.id != libB.id)
        assertEquals("readaloud", libA.mediaType)
        assertEquals("readaloud", libB.mediaType)
    }

    @Test
    fun `commit Storyteller pending materialises Readaloud library with source-scoped id`() = runTest {
        val dao = fakeDao(); val tokens = fakeTokenStorage()
        val libDao = fakeLibraryDao(); val visibility = fakeVisibilityStore()
        val absApi = AbsApi { _, _, _, _ -> error("not called") }
        val repo = SourceRepositoryImpl(dao, tokens, absApi, storytellerApiNotCalled, fakeServerInfoApi, libsApiNotCalled, libDao, fakeLibraryItemDao(), visibility, fakeFilesCleaner())

        val pending = PendingSource(
            url = SourceUrl.parse("http://media-source:8001")!!,
            username = "plamen", userId = "", token = "tok-st", password = "",
            insecureConnectionAllowed = false,
            libraries = emptyList(),
            serverType = ServerType.STORYTELLER_SERVICE,
        )

        val result = repo.commit(pending, hiddenLibraryIds = emptySet())

        assertTrue(result is CommitSourceResult.Success)
        val source = (result as CommitSourceResult.Success).source
        val lib = libDao.allEntities().single()
        assertEquals(SourceRepositoryImpl.readaloudLibraryId(source.id), lib.id)
        assertEquals("readaloud", lib.mediaType)
        assertEquals(source.id, lib.sourceId)
    }

    @Test
    fun `commit Storyteller source is never marked active even when no source is active`() = runTest {
        val dao = fakeDao(); val tokens = fakeTokenStorage()
        val libDao = fakeLibraryDao(); val visibility = fakeVisibilityStore()
        val absApi = AbsApi { _, _, _, _ -> error("not called") }
        val repo = SourceRepositoryImpl(dao, tokens, absApi, storytellerApiNotCalled, fakeServerInfoApi, libsApiNotCalled, libDao, fakeLibraryItemDao(), visibility, fakeFilesCleaner())

        val pending = PendingSource(
            url = SourceUrl.parse("http://media-source:8001")!!,
            username = "plamen", userId = "", token = "tok-st", password = "",
            insecureConnectionAllowed = false,
            libraries = emptyList(),
            serverType = ServerType.STORYTELLER_SERVICE,
        )

        val result = repo.commit(pending, hiddenLibraryIds = emptySet())

        assertTrue(result is CommitSourceResult.Success)
        // Storyteller is a Settings-only readaloud backend (ADR 0026) — it must never become the
        // active browsable Source, even when it is the first source added.
        assertFalse((result as CommitSourceResult.Success).source.isActive)
        assertEquals(null, dao.getActive())
    }

    @Test
    fun `commit persists serverType STORYTELLER and round-trips it`() = runTest {
        val dao = fakeDao(); val tokens = fakeTokenStorage()
        val libDao = fakeLibraryDao(); val visibility = fakeVisibilityStore()
        val absApi = AbsApi { _, _, _, _ -> error("not called") }
        val repo = SourceRepositoryImpl(dao, tokens, absApi, storytellerApiNotCalled, fakeServerInfoApi, libsApiNotCalled, libDao, fakeLibraryItemDao(), visibility, fakeFilesCleaner())

        val pending = PendingSource(
            url = SourceUrl.parse("http://media-source:8001")!!,
            username = "plamen", userId = "uid-1", token = "tok-st", password = "",
            insecureConnectionAllowed = false,
            libraries = emptyList(),
            serverType = com.riffle.core.domain.ServerType.STORYTELLER_SERVICE,
        )

        val result = repo.commit(pending, hiddenLibraryIds = emptySet())

        assertTrue(result is CommitSourceResult.Success)
        val source = (result as CommitSourceResult.Success).source
        assertEquals(com.riffle.core.domain.ServerType.STORYTELLER_SERVICE, source.serverType)
    }

    @Test
    fun `remove deletes source entity and token`() = runTest {
        val entity = SourceEntity("srv-1", "https://abs.example.com", true, false, username = "")
        val dao = fakeDao(entity)
        val tokens = fakeTokenStorage()
        tokens.tokens["srv-1"] = "tok"
        val absApi = AbsApi { _, _, _, _ -> NetworkResult.Auth }
        val repo = SourceRepositoryImpl(
            dao, tokens, absApi, storytellerApiNotCalled, fakeServerInfoApi, libsApiNotCalled, fakeLibraryDao(), fakeLibraryItemDao(), fakeVisibilityStore(), fakeFilesCleaner()
        )
        repo.remove("srv-1")
        assertTrue("token not deleted", tokens.tokens.isEmpty())
        assertNull("entity not deleted from store", dao.getActive())
    }

    @Test
    fun `commit persists the user-entered password alongside the token`() = runTest {
        val dao = fakeDao(); val tokens = fakeTokenStorage()
        val libDao = fakeLibraryDao(); val visibility = fakeVisibilityStore()
        val absApi = AbsApi { _, _, _, _ -> error("not called") }
        val repo = SourceRepositoryImpl(dao, tokens, absApi, storytellerApiNotCalled, fakeServerInfoApi, libsApiNotCalled, libDao, fakeLibraryItemDao(), visibility, fakeFilesCleaner())

        val pending = PendingSource(
            url = SourceUrl.parse("https://abs.example.com")!!,
            username = "admin", userId = "uid-1", token = "tok-xyz", password = "hunter2",
            insecureConnectionAllowed = false,
            libraries = listOf(Library("lib-1", "Books", "book", false)),
        )

        val result = repo.commit(pending, hiddenLibraryIds = emptySet())
        assertTrue(result is CommitSourceResult.Success)
        val id = (result as CommitSourceResult.Success).source.id
        assertEquals("hunter2", tokens.getPassword(id))
    }

    @Test
    fun `remove also deletes the stored password`() = runTest {
        val entity = SourceEntity("srv-1", "https://abs.example.com", true, false, username = "")
        val dao = fakeDao(entity)
        val tokens = fakeTokenStorage()
        tokens.tokens["srv-1"] = "tok"
        tokens.passwords["srv-1"] = "hunter2"
        val absApi = AbsApi { _, _, _, _ -> NetworkResult.Auth }
        val repo = SourceRepositoryImpl(
            dao, tokens, absApi, storytellerApiNotCalled, fakeServerInfoApi, libsApiNotCalled, fakeLibraryDao(), fakeLibraryItemDao(), fakeVisibilityStore(), fakeFilesCleaner()
        )
        repo.remove("srv-1")
        assertTrue("password not deleted", tokens.passwords.isEmpty())
    }

    @Test
    fun `remove deletes the source's downloaded and cached files on disk`() = runTest {
        val entity = SourceEntity("srv-1", "https://abs.example.com", true, false, username = "")
        val dao = fakeDao(entity)
        val tokens = fakeTokenStorage()
        tokens.tokens["srv-1"] = "tok"
        val cleaner = fakeFilesCleaner()
        val absApi = AbsApi { _, _, _, _ -> NetworkResult.Auth }
        val repo = SourceRepositoryImpl(
            dao, tokens, absApi, storytellerApiNotCalled, fakeServerInfoApi, libsApiNotCalled, fakeLibraryDao(), fakeLibraryItemDao(), fakeVisibilityStore(), cleaner
        )

        repo.remove("srv-1")

        assertEquals("on-disk files not cleaned for the removed source", listOf("srv-1"), cleaner.cleanedServerIds)
    }

    @Test
    fun `remove cascades libraries and library_items for a Storyteller source`() = runTest {
        val entity = SourceEntity("st-1", "http://media-source:8001", true, false, username = "plamen", serverType = "STORYTELLER_SERVICE")
        val dao = fakeDao(entity)
        val tokens = fakeTokenStorage()
        tokens.tokens["st-1"] = "tok-st"
        val libDao = fakeLibraryDao()
        val libraryId = SourceRepositoryImpl.readaloudLibraryId("st-1")
        libDao.rows["st-1"] = mutableListOf(LibraryEntity(libraryId, "Readalouds", "readaloud", "st-1"))
        val itemDao = fakeLibraryItemDao()
        itemDao.seed(libraryId, listOf(
            com.riffle.core.database.LibraryItemEntity("st-1", "1385738337074647", libraryId, "The Martian", "Andy Weir", null, 0f),
            com.riffle.core.database.LibraryItemEntity("st-1", "99", libraryId, "Dune", "Frank Herbert", null, 0f),
        ))
        val absApi = AbsApi { _, _, _, _ -> error("not called") }
        val repo = SourceRepositoryImpl(
            dao, tokens, absApi, storytellerApiNotCalled, fakeServerInfoApi, libsApiNotCalled, libDao, itemDao, fakeVisibilityStore(), fakeFilesCleaner()
        )

        repo.remove("st-1")

        assertEquals("source entity not deleted", 0, dao.allCount())
        assertTrue("token not deleted", tokens.tokens.isEmpty())
        assertTrue("library rows not cleared", libDao.allEntities().isEmpty())
        assertTrue("library_items not deleted via deleteByLibraryId", itemDao.deletedLibraryIds.contains(libraryId))
        assertTrue("library items remain after removal", itemDao.itemsFor(libraryId).isEmpty())
    }

    @Test
    fun `remove cascades libraries and library_items for an ABS source`() = runTest {
        val entity = SourceEntity("abs-1", "https://abs.example.com", true, false, username = "u")
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
        val repo = SourceRepositoryImpl(
            dao, tokens, absApi, storytellerApiNotCalled, fakeServerInfoApi, libsApiNotCalled, libDao, itemDao, fakeVisibilityStore(), fakeFilesCleaner()
        )

        repo.remove("abs-1")

        assertEquals(0, dao.allCount())
        assertTrue(libDao.allEntities().isEmpty())
        assertEquals(setOf("lib-1", "lib-2"), itemDao.deletedLibraryIds.toSet())
    }

    @Test
    fun `setActive changes active source`() = runTest {
        val e1 = SourceEntity("s1", "https://one.example.com", true, false, username = "")
        val e2 = SourceEntity("s2", "https://two.example.com", false, false, username = "")
        val dao = fakeDao(e1, e2)
        val absApi = AbsApi { _, _, _, _ -> NetworkResult.Auth }
        val repo = SourceRepositoryImpl(
            dao, fakeTokenStorage(), absApi, storytellerApiNotCalled, fakeServerInfoApi, libsApiNotCalled, fakeLibraryDao(), fakeLibraryItemDao(), fakeVisibilityStore(), fakeFilesCleaner()
        )
        repo.setActive("s2")
        assertEquals("s2", dao.getActive()?.id)
    }

    @Test
    fun `setActive ignores a Storyteller source so it never becomes the active browsable source`() = runTest {
        val abs = SourceEntity("abs", "https://abs.example.com", true, false, username = "")
        val st = SourceEntity("st", "http://media-source:8001", false, false, username = "", serverType = ServerType.STORYTELLER_SERVICE.name)
        val dao = fakeDao(abs, st)
        val absApi = AbsApi { _, _, _, _ -> NetworkResult.Auth }
        val repo = SourceRepositoryImpl(
            dao, fakeTokenStorage(), absApi, storytellerApiNotCalled, fakeServerInfoApi, libsApiNotCalled, fakeLibraryDao(), fakeLibraryItemDao(), fakeVisibilityStore(), fakeFilesCleaner()
        )

        repo.setActive("st")

        // ADR 0026: a Storyteller Source is a Settings-only readaloud backend and can never be the
        // active browsable Source — the previously active ABS source stays active.
        assertEquals("abs", dao.getActive()?.id)
    }

    // ===== absUserId (annotation-sync cross-device namespace) =====

    @Test
    fun `commit ABS source persists pending userId as absUserId so annotation sync can namespace files`() = runTest {
        // The original WebDAV annotation-sync bug: device A and device B both add the same ABS
        // source, get different randomly-minted local servers.id values, and their WebDAV paths
        // (keyed on servers.id) never overlap — neither sees the other's files. Fix: persist the
        // ABS-side stable user.id at commit time and use it as the WebDAV path namespace.
        val dao = fakeDao(); val tokens = fakeTokenStorage()
        val libDao = fakeLibraryDao(); val visibility = fakeVisibilityStore()
        val absApi = AbsApi { _, _, _, _ -> error("not called") }
        val repo = SourceRepositoryImpl(dao, tokens, absApi, storytellerApiNotCalled, fakeServerInfoApi, libsApiNotCalled, libDao, fakeLibraryItemDao(), visibility, fakeFilesCleaner())

        val result = repo.commit(
            PendingSource(
                url = SourceUrl.parse("https://abs.example.com")!!,
                username = "admin", userId = "abs-user-uuid-shared", token = "tok", password = "",
                insecureConnectionAllowed = false,
                libraries = emptyList(),
            ),
            hiddenLibraryIds = emptySet(),
        )

        assertTrue(result is CommitSourceResult.Success)
        val source = (result as CommitSourceResult.Success).source
        assertEquals("abs-user-uuid-shared", source.absUserId)
        // And it round-trips through the DAO so subsequent reads pick it up without a fetch.
        assertEquals("abs-user-uuid-shared", dao.getById(source.id)?.absUserId)
    }

    @Test
    fun `commit Storyteller source leaves absUserId null — annotations live on ABS, not Storyteller`() = runTest {
        // Storyteller's auth response carries no user id (auth is username + token). Annotations
        // are ABS-side only (ADR 0024), so a Storyteller source has nothing to namespace.
        val dao = fakeDao(); val tokens = fakeTokenStorage()
        val libDao = fakeLibraryDao(); val visibility = fakeVisibilityStore()
        val absApi = AbsApi { _, _, _, _ -> error("not called") }
        val repo = SourceRepositoryImpl(dao, tokens, absApi, storytellerApiNotCalled, fakeServerInfoApi, libsApiNotCalled, libDao, fakeLibraryItemDao(), visibility, fakeFilesCleaner())

        val result = repo.commit(
            PendingSource(
                url = SourceUrl.parse("http://media-source:8001")!!,
                username = "plamen", userId = "", token = "tok-st", password = "",
                insecureConnectionAllowed = false,
                libraries = emptyList(),
                serverType = ServerType.STORYTELLER_SERVICE,
            ),
            hiddenLibraryIds = emptySet(),
        )

        assertTrue(result is CommitSourceResult.Success)
        assertEquals(null, (result as CommitSourceResult.Success).source.absUserId)
    }

    @Test
    fun `ensureAbsUserId returns the persisted value without hitting the network`() = runTest {
        val entity = SourceEntity("abs-1", "https://abs.example.com", true, false, username = "u", absUserId = "persisted-user-id")
        val infoApi = RecordingServerInfoApi(userId = "should-not-be-called")
        val repo = SourceRepositoryImpl(
            fakeDao(entity), fakeTokenStorage(), AbsApi { _, _, _, _ -> error("not called") },
            storytellerApiNotCalled, infoApi, libsApiNotCalled,
            fakeLibraryDao(), fakeLibraryItemDao(), fakeVisibilityStore(), fakeFilesCleaner(),
        )

        val result = repo.ensureAbsUserId("abs-1")

        assertEquals("persisted-user-id", result)
        assertEquals("must not /api/me when value is already cached", 0, infoApi.getCurrentUserIdCalls)
    }

    @Test
    fun `ensureAbsUserId backfills a null column from api me and persists it`() = runTest {
        // Legacy row added before the absUserId column existed. The first sync attempt fetches
        // /api/me, persists the result, and subsequent calls are cache hits.
        val entity = SourceEntity("abs-legacy", "https://abs.example.com", true, false, username = "u", absUserId = null)
        val dao = fakeDao(entity)
        val tokens = fakeTokenStorage().also { it.tokens["abs-legacy"] = "tok-cached" }
        val infoApi = RecordingServerInfoApi(userId = "fetched-user-id")
        val repo = SourceRepositoryImpl(
            dao, tokens, AbsApi { _, _, _, _ -> error("not called") },
            storytellerApiNotCalled, infoApi, libsApiNotCalled,
            fakeLibraryDao(), fakeLibraryItemDao(), fakeVisibilityStore(), fakeFilesCleaner(),
        )

        val first = repo.ensureAbsUserId("abs-legacy")
        val second = repo.ensureAbsUserId("abs-legacy")

        assertEquals("fetched-user-id", first)
        assertEquals("fetched-user-id", second)
        // Persisted, so the second call is a DAO read — not a second network round-trip.
        assertEquals(1, infoApi.getCurrentUserIdCalls)
        assertEquals("fetched-user-id", dao.getById("abs-legacy")?.absUserId)
    }

    @Test
    fun `ensureAbsUserId returns null when api me fails so callers skip sync instead of breaking`() = runTest {
        val entity = SourceEntity("abs-1", "https://abs.example.com", true, false, username = "u", absUserId = null)
        val tokens = fakeTokenStorage().also { it.tokens["abs-1"] = "tok" }
        val infoApi = RecordingServerInfoApi(userId = null) // simulates offline / 5xx / parse error
        val repo = SourceRepositoryImpl(
            fakeDao(entity), tokens, AbsApi { _, _, _, _ -> error("not called") },
            storytellerApiNotCalled, infoApi, libsApiNotCalled,
            fakeLibraryDao(), fakeLibraryItemDao(), fakeVisibilityStore(), fakeFilesCleaner(),
        )

        val result = repo.ensureAbsUserId("abs-1")

        assertNull("sync must skip silently — local DB stays the source of truth", result)
    }

    @Test
    fun `ensureAbsUserId returns null for a Storyteller source without fetching api me`() = runTest {
        val entity = SourceEntity("st-1", "http://media-source:8001", false, false, username = "u", serverType = ServerType.STORYTELLER_SERVICE.name)
        val infoApi = RecordingServerInfoApi(userId = "should-not-be-called")
        val repo = SourceRepositoryImpl(
            fakeDao(entity), fakeTokenStorage(), AbsApi { _, _, _, _ -> error("not called") },
            storytellerApiNotCalled, infoApi, libsApiNotCalled,
            fakeLibraryDao(), fakeLibraryItemDao(), fakeVisibilityStore(), fakeFilesCleaner(),
        )

        val result = repo.ensureAbsUserId("st-1")

        assertNull(result)
        assertEquals("ABS endpoint must not be called for a Storyteller source", 0, infoApi.getCurrentUserIdCalls)
    }

    @Test
    fun `ensureAbsUserId returns null for an unknown source id`() = runTest {
        val repo = SourceRepositoryImpl(
            fakeDao(), fakeTokenStorage(), AbsApi { _, _, _, _ -> error("not called") },
            storytellerApiNotCalled, fakeServerInfoApi, libsApiNotCalled,
            fakeLibraryDao(), fakeLibraryItemDao(), fakeVisibilityStore(), fakeFilesCleaner(),
        )

        assertNull(repo.ensureAbsUserId("does-not-exist"))
    }
}
