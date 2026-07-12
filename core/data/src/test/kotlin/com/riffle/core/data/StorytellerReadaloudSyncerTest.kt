package com.riffle.core.data

import com.riffle.core.network.NetworkResult

import com.riffle.core.domain.AuthenticateResult
import com.riffle.core.domain.CommitSourceResult
import com.riffle.core.domain.PendingSource
import com.riffle.core.domain.Source
import com.riffle.core.domain.SourceRepository
import com.riffle.core.domain.ServerType
import com.riffle.core.domain.SourceUrl
import com.riffle.core.domain.TokenStorage
import com.riffle.core.network.NetworkStorytellerBook
import com.riffle.core.network.StorytellerLibraryApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.IOException

// ── helpers ──────────────────────────────────────────────────────────────────

private fun stServer(id: String) = Source(
    id = id,
    url = SourceUrl.parse("http://st-$id:8001")!!,
    isActive = true,
    insecureConnectionAllowed = false,
    username = "user",
    serverType = ServerType.STORYTELLER_SERVICE,
)

private fun absServer(id: String) = Source(
    id = id,
    url = SourceUrl.parse("http://abs-$id:13378")!!,
    isActive = false,
    insecureConnectionAllowed = false,
    username = "user",
    serverType = ServerType.AUDIOBOOKSHELF,
)

private fun fakeServers(list: List<Source>): SourceRepository = object : SourceRepository {
    override fun observeAll(): Flow<List<Source>> = flowOf(list)
    override suspend fun getActive(): Source? = list.firstOrNull { it.isActive }
    override suspend fun getById(sourceId: String): Source? = list.firstOrNull { it.id == sourceId }
    override suspend fun commit(pending: PendingSource, hiddenLibraryIds: Set<String>): CommitSourceResult = error("unused")
    override suspend fun setActive(sourceId: String) = error("unused")
    override suspend fun remove(sourceId: String) = error("unused")
    override suspend fun getSourceVersion(sourceId: String): String? = error("unused")
}

private fun fakeTokens(map: Map<String, String>): TokenStorage = object : TokenStorage {
    override suspend fun saveToken(sourceId: String, token: String) = error("unused")
    override suspend fun getToken(sourceId: String): String? = map[sourceId]
    override suspend fun deleteToken(sourceId: String) = error("unused")
}

private class CapturingApi(
    private val result: (String) -> NetworkResult<List<NetworkStorytellerBook>>,
) : StorytellerLibraryApi {
    val calls = mutableListOf<String>()

    override suspend fun listReadalouds(
        baseUrl: String, token: String, insecureAllowed: Boolean,
    ): NetworkResult<List<NetworkStorytellerBook>> {
        calls += baseUrl
        return result(baseUrl)
    }

    override suspend fun validateToken(baseUrl: String, token: String, insecureAllowed: Boolean): NetworkResult<Boolean> =
        error("unused")

    override suspend fun getBook(baseUrl: String, bookId: Long, token: String, insecureAllowed: Boolean): NetworkResult<NetworkStorytellerBook> =
        error("unused")

    override fun coverUrl(baseUrl: String, bookId: Long): String = "$baseUrl/api/books/$bookId/cover"
}

private fun capturingApi(books: List<NetworkStorytellerBook>): CapturingApi =
    CapturingApi { NetworkResult.Success(books) }

private fun capturingApiError(): CapturingApi =
    CapturingApi { NetworkResult.Offline(IOException("down")) }

// ── mapping test (pre-existing) ───────────────────────────────────────────────

class StorytellerReadaloudSyncerTest {
    @Test fun `mapping builds entities preserving identifiers and local progress`() {
        val books = listOf(
            NetworkStorytellerBook(id = 42L, title = "Dune", authors = listOf("Frank Herbert", "Brian Herbert"), isbn = "111", asin = "B01"),
        )
        val entities = storytellerBooksToEntities(
            books = books,
            sourceId = "st-1",
            libraryId = "readaloud:st-1",
            coverUrlOf = { id -> "http://s/api/books/$id/cover" },
            lastOpenedAtMap = mapOf("42" to 1234L),
            progressMap = mapOf("42" to 0.5f),
            addedAtDefault = 9_999L,
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

    @Test fun `syncStale fetches each storyteller source and stores under readaloud library id`() = runTest {
        val itemDao = FakeLibraryItemDao()
        val api = capturingApi(books = listOf(NetworkStorytellerBook(id = 1L, title = "T", authors = listOf("A"))))
        val syncer = StorytellerReadaloudSyncer(
            sourceRepository = fakeServers(listOf(stServer("st-1"), absServer("abs-1"))),
            tokenStorage = fakeTokens(mapOf("st-1" to "tok")),
            storytellerApi = api,
            libraryItemDao = itemDao,
            clock = { 0L },
        )
        syncer.syncStale()
        assertEquals(listOf("http://st-st-1:8001"), api.calls)   // only the storyteller source fetched
        assertEquals(1, itemDao.itemsFor("readaloud:st-1").size)
    }

    @Test fun `syncStale respects the staleness ttl per source`() = runTest {
        val itemDao = FakeLibraryItemDao()
        val api = capturingApi(books = listOf(NetworkStorytellerBook(id = 1L, title = "T", authors = listOf("A"))))
        var now = 0L
        val syncer = StorytellerReadaloudSyncer(
            sourceRepository = fakeServers(listOf(stServer("st-1"))),
            tokenStorage = fakeTokens(mapOf("st-1" to "tok")),
            storytellerApi = api,
            libraryItemDao = itemDao,
            clock = { now },
        )
        syncer.syncStale()
        now = 9 * 60 * 1000L
        syncer.syncStale()
        assertEquals(1, api.calls.size)   // throttled within TTL
        now = 11 * 60 * 1000L
        syncer.syncStale()
        assertEquals(2, api.calls.size)   // refetched after TTL
    }

    @Test fun `syncStale is best-effort - a failing source does not throw or record success`() = runTest {
        val itemDao = FakeLibraryItemDao()
        val api = capturingApiError()
        var now = 0L
        val syncer = StorytellerReadaloudSyncer(
            sourceRepository = fakeServers(listOf(stServer("st-1"))),
            tokenStorage = fakeTokens(mapOf("st-1" to "tok")),
            storytellerApi = api,
            libraryItemDao = itemDao,
            clock = { now },
        )
        syncer.syncStale()
        assertEquals(0, itemDao.itemsFor("readaloud:st-1").size)
        now = 1L
        syncer.syncStale()
        assertEquals(2, api.calls.size)   // not recorded as synced → retried
    }
}
