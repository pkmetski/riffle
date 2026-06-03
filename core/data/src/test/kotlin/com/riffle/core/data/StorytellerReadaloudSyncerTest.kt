package com.riffle.core.data

import com.riffle.core.domain.AuthenticateResult
import com.riffle.core.domain.CommitServerResult
import com.riffle.core.domain.PendingServer
import com.riffle.core.domain.Server
import com.riffle.core.domain.ServerRepository
import com.riffle.core.domain.ServerType
import com.riffle.core.domain.ServerUrl
import com.riffle.core.domain.TokenStorage
import com.riffle.core.network.NetworkStorytellerBook
import com.riffle.core.network.NetworkStorytellerBookResult
import com.riffle.core.network.NetworkStorytellerBooksResult
import com.riffle.core.network.NetworkStorytellerValidateResult
import com.riffle.core.network.StorytellerLibraryApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.IOException

// ── helpers ──────────────────────────────────────────────────────────────────

private fun stServer(id: String) = Server(
    id = id,
    url = ServerUrl.parse("http://st-$id:8001")!!,
    isActive = true,
    insecureConnectionAllowed = false,
    username = "user",
    serverType = ServerType.STORYTELLER,
)

private fun absServer(id: String) = Server(
    id = id,
    url = ServerUrl.parse("http://abs-$id:13378")!!,
    isActive = false,
    insecureConnectionAllowed = false,
    username = "user",
    serverType = ServerType.AUDIOBOOKSHELF,
)

private fun fakeServers(list: List<Server>): ServerRepository = object : ServerRepository {
    override fun observeAll(): Flow<List<Server>> = flowOf(list)
    override suspend fun getActive(): Server? = list.firstOrNull { it.isActive }
    override suspend fun getById(serverId: String): Server? = list.firstOrNull { it.id == serverId }
    override suspend fun authenticate(
        url: ServerUrl, username: String, password: String,
        insecureAllowed: Boolean, serverType: ServerType,
    ): AuthenticateResult = error("unused")
    override suspend fun commit(pending: PendingServer, hiddenLibraryIds: Set<String>): CommitServerResult = error("unused")
    override suspend fun setActive(serverId: String) = error("unused")
    override suspend fun remove(serverId: String) = error("unused")
    override suspend fun getServerVersion(serverId: String): String? = error("unused")
}

private fun fakeTokens(map: Map<String, String>): TokenStorage = object : TokenStorage {
    override suspend fun saveToken(serverId: String, token: String) = error("unused")
    override suspend fun getToken(serverId: String): String? = map[serverId]
    override suspend fun deleteToken(serverId: String) = error("unused")
}

private class CapturingApi(
    private val result: (String) -> NetworkStorytellerBooksResult,
) : StorytellerLibraryApi {
    val calls = mutableListOf<String>()

    override suspend fun listReadalouds(
        baseUrl: String, token: String, insecureAllowed: Boolean,
    ): NetworkStorytellerBooksResult {
        calls += baseUrl
        return result(baseUrl)
    }

    override suspend fun validateToken(baseUrl: String, token: String, insecureAllowed: Boolean): NetworkStorytellerValidateResult =
        error("unused")

    override suspend fun getBook(baseUrl: String, bookId: Long, token: String, insecureAllowed: Boolean): NetworkStorytellerBookResult =
        error("unused")

    override fun coverUrl(baseUrl: String, bookId: Long): String = "$baseUrl/api/books/$bookId/cover"
}

private fun capturingApi(books: List<NetworkStorytellerBook>): CapturingApi =
    CapturingApi { NetworkStorytellerBooksResult.Success(books) }

private fun capturingApiError(): CapturingApi =
    CapturingApi { NetworkStorytellerBooksResult.NetworkError(IOException("down")) }

// ── mapping test (pre-existing) ───────────────────────────────────────────────

class StorytellerReadaloudSyncerTest {
    @Test fun `mapping builds entities preserving identifiers and local progress`() {
        val books = listOf(
            NetworkStorytellerBook(id = 42L, title = "Dune", authors = listOf("Frank Herbert", "Brian Herbert"), isbn = "111", asin = "B01"),
        )
        val entities = storytellerBooksToEntities(
            books = books,
            libraryId = "readaloud:st-1",
            coverUrlOf = { id -> "http://s/api/books/$id/cover" },
            lastOpenedAtMap = mapOf("42" to 1234L),
            progressMap = mapOf("42" to 0.5f),
        )
        assertEquals(1, entities.size)
        val e = entities[0]
        assertEquals("42", e.id)
        assertEquals("readaloud:st-1", e.libraryId)
        assertEquals("Dune", e.title)
        assertEquals("Frank Herbert, Brian Herbert", e.author)
        assertEquals("111", e.isbn)
        assertEquals("B01", e.asin)
        assertEquals("http://s/api/books/42/cover", e.coverUrl)
        assertEquals(0.5f, e.readingProgress)
        assertEquals(1234L, e.lastOpenedAt)
    }

    @Test fun `syncStale fetches each storyteller server and stores under readaloud library id`() = runTest {
        val itemDao = FakeLibraryItemDao()
        val api = capturingApi(books = listOf(NetworkStorytellerBook(id = 1L, title = "T", authors = listOf("A"))))
        val syncer = StorytellerReadaloudSyncer(
            serverRepository = fakeServers(listOf(stServer("st-1"), absServer("abs-1"))),
            tokenStorage = fakeTokens(mapOf("st-1" to "tok")),
            storytellerApi = api,
            libraryItemDao = itemDao,
            clock = { 0L },
        )
        syncer.syncStale()
        assertEquals(listOf("http://st-st-1:8001"), api.calls)   // only the storyteller server fetched
        assertEquals(1, itemDao.itemsFor("readaloud:st-1").size)
    }

    @Test fun `syncStale respects the staleness ttl per server`() = runTest {
        val itemDao = FakeLibraryItemDao()
        val api = capturingApi(books = listOf(NetworkStorytellerBook(id = 1L, title = "T", authors = listOf("A"))))
        var now = 0L
        val syncer = StorytellerReadaloudSyncer(fakeServers(listOf(stServer("st-1"))), fakeTokens(mapOf("st-1" to "tok")), api, itemDao, clock = { now })
        syncer.syncStale()
        now = 9 * 60 * 1000L
        syncer.syncStale()
        assertEquals(1, api.calls.size)   // throttled within TTL
        now = 11 * 60 * 1000L
        syncer.syncStale()
        assertEquals(2, api.calls.size)   // refetched after TTL
    }

    @Test fun `syncStale is best-effort - a failing server does not throw or record success`() = runTest {
        val itemDao = FakeLibraryItemDao()
        val api = capturingApiError()
        var now = 0L
        val syncer = StorytellerReadaloudSyncer(fakeServers(listOf(stServer("st-1"))), fakeTokens(mapOf("st-1" to "tok")), api, itemDao, clock = { now })
        syncer.syncStale()
        assertEquals(0, itemDao.itemsFor("readaloud:st-1").size)
        now = 1L
        syncer.syncStale()
        assertEquals(2, api.calls.size)   // not recorded as synced → retried
    }
}
