package com.riffle.core.data

import com.riffle.core.database.LibraryDao
import com.riffle.core.database.LibraryEntity
import com.riffle.core.database.LibraryItemDao
import com.riffle.core.database.LibraryItemEntity
import com.riffle.core.domain.AddServerResult
import com.riffle.core.domain.Library
import com.riffle.core.domain.LibraryRefreshResult
import com.riffle.core.domain.Server
import com.riffle.core.domain.ServerRepository
import com.riffle.core.domain.ServerUrl
import com.riffle.core.domain.TokenStorage
import com.riffle.core.network.AbsLibraryApi
import com.riffle.core.network.NetworkLibrariesResult
import com.riffle.core.network.NetworkLibrary
import com.riffle.core.network.NetworkLibraryItem
import com.riffle.core.network.NetworkLibraryItemsResult
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class LibraryRepositoryTest {

    private val fakeServerRepository = object : ServerRepository {
        var activeServer: Server? = null
        override fun observeAll() = MutableStateFlow(listOfNotNull(activeServer))
        override suspend fun getActive() = activeServer
        override suspend fun addServer(url: ServerUrl, username: String, password: String, insecureAllowed: Boolean): AddServerResult =
            AddServerResult.NetworkError(IOException())
        override suspend fun setActive(serverId: String) {}
        override suspend fun remove(serverId: String) {}
    }

    private val fakeTokenStorage = object : TokenStorage {
        val tokens = mutableMapOf<String, String>()
        override suspend fun saveToken(serverId: String, token: String) { tokens[serverId] = token }
        override suspend fun getToken(serverId: String) = tokens[serverId]
        override suspend fun deleteToken(serverId: String) { tokens.remove(serverId) }
    }

    private class FakeLibraryDao : LibraryDao {
        val upserted = mutableListOf<LibraryEntity>()
        private val roomData = mutableMapOf<String, MutableStateFlow<List<LibraryEntity>>>()

        fun seedData(serverId: String, entities: List<LibraryEntity>) {
            roomData.getOrPut(serverId) { MutableStateFlow(emptyList()) }.value = entities
        }

        override fun observeByServerId(serverId: String): Flow<List<LibraryEntity>> =
            roomData.getOrPut(serverId) { MutableStateFlow(emptyList()) }

        override suspend fun upsertAll(libraries: List<LibraryEntity>) {
            upserted.addAll(libraries)
            libraries.groupBy { it.serverId }.forEach { (serverId, items) ->
                roomData.getOrPut(serverId) { MutableStateFlow(emptyList()) }.value = items
            }
        }

        override suspend fun deleteByServerId(serverId: String) {
            roomData[serverId]?.value = emptyList()
        }
    }

    private class FakeLibraryItemDao : LibraryItemDao {
        val upserted = mutableListOf<LibraryItemEntity>()
        private val roomData = mutableMapOf<String, MutableStateFlow<List<LibraryItemEntity>>>()

        override fun observeByLibraryId(libraryId: String): Flow<List<LibraryItemEntity>> =
            roomData.getOrPut(libraryId) { MutableStateFlow(emptyList()) }

        override suspend fun upsertAll(items: List<LibraryItemEntity>) {
            upserted.addAll(items)
            items.groupBy { it.libraryId }.forEach { (libraryId, entities) ->
                roomData.getOrPut(libraryId) { MutableStateFlow(emptyList()) }.value = entities
            }
        }

        override suspend fun deleteByLibraryId(libraryId: String) {
            roomData[libraryId]?.value = emptyList()
        }
    }

    private fun makeRepo(
        libraryDao: FakeLibraryDao = FakeLibraryDao(),
        libraryItemDao: FakeLibraryItemDao = FakeLibraryItemDao(),
        api: AbsLibraryApi = object : AbsLibraryApi {
            override suspend fun getLibraries(baseUrl: String, token: String, insecureAllowed: Boolean) =
                NetworkLibrariesResult.Success(emptyList())
            override suspend fun getLibraryItems(baseUrl: String, libraryId: String, token: String, insecureAllowed: Boolean) =
                NetworkLibraryItemsResult.Success(emptyList())
        },
    ) = LibraryRepositoryImpl(api, libraryDao, libraryItemDao, fakeServerRepository, fakeTokenStorage)

    private fun activeServer(id: String = "s1") = Server(
        id = id,
        url = ServerUrl.parse("https://abs.example.com")!!,
        displayName = "abs",
        isActive = true,
        insecureConnectionAllowed = false,
    )

    // ── refreshLibraries ─────────────────────────────────────────────────────

    @Test
    fun `refreshLibraries filters non-book libraries`() = runTest {
        fakeServerRepository.activeServer = activeServer()
        fakeTokenStorage.tokens["s1"] = "tok"
        val dao = FakeLibraryDao()
        val api = object : AbsLibraryApi {
            override suspend fun getLibraries(baseUrl: String, token: String, insecureAllowed: Boolean) =
                NetworkLibrariesResult.Success(listOf(
                    NetworkLibrary("lib-1", "Books", "book"),
                    NetworkLibrary("lib-2", "Podcasts", "podcast"),
                ))
            override suspend fun getLibraryItems(baseUrl: String, libraryId: String, token: String, insecureAllowed: Boolean) =
                NetworkLibraryItemsResult.Success(emptyList())
        }
        makeRepo(libraryDao = dao, api = api).refreshLibraries()
        assertEquals(1, dao.upserted.size)
        assertEquals("book", dao.upserted[0].mediaType)
    }

    @Test
    fun `refreshLibraries caches to Room with correct serverId`() = runTest {
        fakeServerRepository.activeServer = activeServer()
        fakeTokenStorage.tokens["s1"] = "tok"
        val dao = FakeLibraryDao()
        val api = object : AbsLibraryApi {
            override suspend fun getLibraries(baseUrl: String, token: String, insecureAllowed: Boolean) =
                NetworkLibrariesResult.Success(listOf(NetworkLibrary("lib-1", "Books", "book")))
            override suspend fun getLibraryItems(baseUrl: String, libraryId: String, token: String, insecureAllowed: Boolean) =
                NetworkLibraryItemsResult.Success(emptyList())
        }
        makeRepo(libraryDao = dao, api = api).refreshLibraries()
        assertEquals("s1", dao.upserted[0].serverId)
    }

    @Test
    fun `refreshLibraries returns NetworkError when network fails`() = runTest {
        fakeServerRepository.activeServer = activeServer()
        fakeTokenStorage.tokens["s1"] = "tok"
        val api = object : AbsLibraryApi {
            override suspend fun getLibraries(baseUrl: String, token: String, insecureAllowed: Boolean) =
                NetworkLibrariesResult.NetworkError(IOException("timeout"))
            override suspend fun getLibraryItems(baseUrl: String, libraryId: String, token: String, insecureAllowed: Boolean) =
                NetworkLibraryItemsResult.Success(emptyList())
        }
        val result = makeRepo(api = api).refreshLibraries()
        assertTrue(result is LibraryRefreshResult.NetworkError)
    }

    @Test
    fun `refreshLibraries returns NoActiveServer when no server configured`() = runTest {
        fakeServerRepository.activeServer = null
        val result = makeRepo().refreshLibraries()
        assertTrue(result is LibraryRefreshResult.NoActiveServer)
    }

    // ── observeLibraries ─────────────────────────────────────────────────────

    @Test
    fun `observeLibraries emits from Room for active server`() = runTest {
        fakeServerRepository.activeServer = activeServer()
        val dao = FakeLibraryDao()
        dao.seedData("s1", listOf(LibraryEntity("lib-1", "Books", "book", "s1")))
        val result = makeRepo(libraryDao = dao).observeLibraries().first()
        assertEquals(1, result.size)
        assertEquals("lib-1", result[0].id)
    }

    @Test
    fun `observeLibraries emits empty list when no active server`() = runTest {
        fakeServerRepository.activeServer = null
        val result = makeRepo().observeLibraries().first()
        assertTrue(result.isEmpty())
    }

    // ── refreshLibraryItems ───────────────────────────────────────────────────

    @Test
    fun `refreshLibraryItems caches items to Room`() = runTest {
        fakeServerRepository.activeServer = activeServer()
        fakeTokenStorage.tokens["s1"] = "tok"
        val dao = FakeLibraryItemDao()
        val api = object : AbsLibraryApi {
            override suspend fun getLibraries(baseUrl: String, token: String, insecureAllowed: Boolean) =
                NetworkLibrariesResult.Success(emptyList())
            override suspend fun getLibraryItems(baseUrl: String, libraryId: String, token: String, insecureAllowed: Boolean) =
                NetworkLibraryItemsResult.Success(listOf(
                    NetworkLibraryItem("item-1", "lib-1", "My Book", "Author A", 0.42f)
                ))
        }
        makeRepo(libraryItemDao = dao, api = api).refreshLibraryItems("lib-1")
        assertEquals(1, dao.upserted.size)
        assertEquals("item-1", dao.upserted[0].id)
        assertEquals(0.42f, dao.upserted[0].readingProgress, 0.001f)
    }

    @Test
    fun `refreshLibraryItems returns NetworkError when network fails`() = runTest {
        fakeServerRepository.activeServer = activeServer()
        fakeTokenStorage.tokens["s1"] = "tok"
        val api = object : AbsLibraryApi {
            override suspend fun getLibraries(baseUrl: String, token: String, insecureAllowed: Boolean) =
                NetworkLibrariesResult.Success(emptyList())
            override suspend fun getLibraryItems(baseUrl: String, libraryId: String, token: String, insecureAllowed: Boolean) =
                NetworkLibraryItemsResult.NetworkError(IOException("timeout"))
        }
        val result = makeRepo(api = api).refreshLibraryItems("lib-1")
        assertTrue(result is LibraryRefreshResult.NetworkError)
    }

    // ── observeLibraryItems ───────────────────────────────────────────────────

    @Test
    fun `observeLibraryItems emits from Room`() = runTest {
        val dao = FakeLibraryItemDao()
        dao.upsertAll(listOf(LibraryItemEntity("item-1", "lib-1", "My Book", "Author A", null, 0.5f, false)))
        val result = makeRepo(libraryItemDao = dao).observeLibraryItems("lib-1").first()
        assertEquals(1, result.size)
        assertEquals("item-1", result[0].id)
        assertTrue(result[0].isCached)
    }
}
