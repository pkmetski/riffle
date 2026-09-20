package com.riffle.shared.reader

import com.riffle.core.domain.SourceRepository
import com.riffle.core.domain.TokenStorage
import com.riffle.core.models.LibraryItem
import com.riffle.core.models.Source
import com.riffle.core.domain.CommitSourceResult
import com.riffle.core.domain.PendingSource
import com.riffle.core.models.EbookFormat
import com.riffle.core.models.SourceUrl
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The bug this pins is invisible with one source configured, which is why it survived: every
 * iOS fetcher resolved `sourceRepository.getActive()` while holding a `LibraryItem` that carries
 * its own `sourceId`. With two sources, opening a book from the non-active one fetched from the
 * wrong host with the wrong token. So every case here has two sources and asks for the one that
 * is NOT active.
 */
class ItemSourceEndpointTest {

    private fun source(id: String, url: String) = Source(
        id = id,
        url = SourceUrl.parse(url)!!,
        isActive = false,
        insecureConnectionAllowed = false,
        username = "user",
    )

    private class FakeSources(
        private val byId: Map<String, Source>,
        private val active: Source?,
    ) : SourceRepository {
        override fun observeAll(): Flow<List<Source>> = flowOf(byId.values.toList())
        override suspend fun getActive(): Source? = active
        override suspend fun getById(sourceId: String): Source? = byId[sourceId]
        override suspend fun commit(
            pending: PendingSource,
            hiddenLibraryIds: Set<String>,
        ): CommitSourceResult = CommitSourceResult.Failure(UnsupportedOperationException())
        override suspend fun setActive(sourceId: String) = Unit
        override suspend fun remove(sourceId: String) = Unit
        override suspend fun getSourceVersion(sourceId: String): String? = null
    }

    private class FakeTokens(private val byId: Map<String, String>) : TokenStorage {
        override suspend fun saveToken(sourceId: String, token: String) = Unit
        override suspend fun getToken(sourceId: String): String? = byId[sourceId]
        override suspend fun deleteToken(sourceId: String) = Unit
    }

    private fun item(sourceId: String) = LibraryItem(
        id = "item-1",
        sourceId = sourceId,
        libraryId = "lib-1",
        title = "T",
        author = "A",
        coverUrl = null,
        readingProgress = 0f,
        isCached = false,
        isDownloaded = false,
        ebookFormat = EbookFormat.Epub,
        ebookFileIno = "ino-1",
    )

    private val home = source("home", "https://home.test")
    private val away = source("away", "https://away.test")

    @Test
    fun resolvesTheItemsOwnSourceNotTheActiveOne() = runTest {
        val endpoint = resolveItemEndpoint(
            FakeSources(mapOf("home" to home, "away" to away), active = home),
            FakeTokens(mapOf("home" to "home-token", "away" to "away-token")),
            item("away"),
        )

        assertEquals("away", endpoint?.source?.id, "must resolve the item's source, not the active one")
        assertEquals("away-token", endpoint?.token, "must use the token belonging to that source")
    }

    @Test
    fun buildsTheFileUrlAgainstTheItemsOwnHost() = runTest {
        val endpoint = resolveItemEndpoint(
            FakeSources(mapOf("home" to home, "away" to away), active = home),
            FakeTokens(mapOf("home" to "home-token", "away" to "away-token")),
            item("away"),
        )!!

        assertEquals(
            "https://away.test/api/items/item-1/file/ino-9",
            endpoint.absFileUrl(item("away"), "ino-9"),
        )
    }

    @Test
    fun aTrailingSlashOnTheSourceUrlDoesNotDoubleUp() = runTest {
        val endpoint = resolveItemEndpoint(
            FakeSources(mapOf("s" to source("s", "https://host.test/")), active = null),
            FakeTokens(mapOf("s" to "t")),
            item("s"),
        )!!

        assertEquals("https://host.test/api/items/item-1/file/ino-1", endpoint.absFileUrl(item("s"), "ino-1"))
    }

    @Test
    fun anUnknownSourceResolvesToNullRatherThanFallingBackToTheActiveOne() = runTest {
        val endpoint = resolveItemEndpoint(
            FakeSources(mapOf("home" to home), active = home),
            FakeTokens(mapOf("home" to "home-token")),
            item("deleted"),
        )

        assertNull(endpoint, "falling back to the active source would fetch from a host that lacks the item")
    }

    @Test
    fun aSourceWithNoStoredTokenResolvesToNull() = runTest {
        val endpoint = resolveItemEndpoint(
            FakeSources(mapOf("away" to away), active = null),
            FakeTokens(emptyMap()),
            item("away"),
        )

        assertNull(endpoint)
    }
}
