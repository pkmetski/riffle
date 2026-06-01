package com.riffle.core.data

import com.riffle.core.database.LastOpenedAtRow
import com.riffle.core.database.LibraryItemDao
import com.riffle.core.database.LibraryItemEntity
import com.riffle.core.database.MatchableItemRow
import com.riffle.core.database.ReadaloudLinkDao
import com.riffle.core.database.ReadaloudLinkEntity
import com.riffle.core.database.ReadingProgressRow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReadaloudMatchingServiceTest {

    @Test
    fun `Tier 1 ISBN match across servers produces Confirmed auto-link`() = runTest {
        val items = StubLibraryItemDao(
            storyteller = listOf(
                row(serverId = "st-1", itemId = "42", title = "x", author = "x", isbn = "9780261103573"),
            ),
            abs = listOf(
                row(serverId = "abs-1", itemId = "item-1", title = "y", author = "y", isbn = "9780261103573"),
                row(serverId = "abs-2", itemId = "item-2", title = "z", author = "z", isbn = "9999999999999"),
            ),
        )
        val links = RecordingReadaloudLinkDao()

        ReadaloudMatchingService(items, links, clock = { 12345L }).reconcileLinks()

        assertEquals(1, links.upserts.size)
        val link = links.upserts.single()
        assertEquals("st-1", link.storytellerServerId)
        assertEquals("42", link.storytellerBookId)
        assertEquals("abs-1", link.absServerId)
        assertEquals("item-1", link.absLibraryItemId)
        assertEquals(false, link.userConfirmed)
        assertEquals(12345L, link.createdAt)
        assertEquals(12345L, link.updatedAt)
    }

    @Test
    fun `ISBN collision across two ABS servers leaves the book unmatched`() = runTest {
        val items = StubLibraryItemDao(
            storyteller = listOf(row(serverId = "st-1", itemId = "42", isbn = "9780261103573")),
            abs = listOf(
                row(serverId = "abs-1", itemId = "a", isbn = "9780261103573"),
                row(serverId = "abs-2", itemId = "b", isbn = "9780261103573"),
            ),
        )
        val links = RecordingReadaloudLinkDao()

        ReadaloudMatchingService(items, links).reconcileLinks()

        assertTrue("no auto-link should be created on a Tier 1 collision", links.upserts.isEmpty())
    }

    @Test
    fun `userConfirmed links are sticky and never touched by the auto-matcher`() = runTest {
        val items = StubLibraryItemDao(
            storyteller = listOf(row(serverId = "st-1", itemId = "42", isbn = "9780261103573")),
            abs = listOf(
                // ABS-side ISBN now points elsewhere — the auto-matcher would re-link if not sticky.
                row(serverId = "abs-2", itemId = "different-item", isbn = "9780261103573"),
            ),
        )
        val links = RecordingReadaloudLinkDao().apply {
            seed(
                ReadaloudLinkEntity(
                    storytellerServerId = "st-1",
                    storytellerBookId = "42",
                    absServerId = "abs-1",
                    absLibraryItemId = "user-chosen",
                    state = ReadaloudLinkEntity.STATE_CONFIRMED,
                    userConfirmed = true,
                    createdAt = 1L,
                    updatedAt = 1L,
                ),
            )
        }

        ReadaloudMatchingService(items, links).reconcileLinks()

        assertTrue("userConfirmed link must not be overwritten", links.upserts.isEmpty())
        assertTrue("userConfirmed link must not be deleted", links.deletions.isEmpty())
    }

    @Test
    fun `auto-Confirmed link is rewritten when the matcher's verdict changes`() = runTest {
        val items = StubLibraryItemDao(
            storyteller = listOf(row(serverId = "st-1", itemId = "42", isbn = "9780261103573")),
            abs = listOf(
                row(serverId = "abs-2", itemId = "moved-here", isbn = "9780261103573"),
            ),
        )
        val links = RecordingReadaloudLinkDao().apply {
            seed(
                ReadaloudLinkEntity(
                    storytellerServerId = "st-1",
                    storytellerBookId = "42",
                    absServerId = "abs-1",
                    absLibraryItemId = "old-target",
                    state = ReadaloudLinkEntity.STATE_CONFIRMED,
                    userConfirmed = false,
                    createdAt = 100L,
                    updatedAt = 100L,
                ),
            )
        }

        ReadaloudMatchingService(items, links, clock = { 500L }).reconcileLinks()

        val link = links.upserts.single()
        assertEquals("abs-2", link.absServerId)
        assertEquals("moved-here", link.absLibraryItemId)
        // createdAt preserved across re-link; updatedAt refreshed.
        assertEquals(100L, link.createdAt)
        assertEquals(500L, link.updatedAt)
    }

    @Test
    fun `stale auto-Confirmed link is deleted when the matcher can no longer confirm`() = runTest {
        val items = StubLibraryItemDao(
            storyteller = listOf(row(serverId = "st-1", itemId = "42", title = "Dune", author = "Frank Herbert", isbn = "9780261103573")),
            abs = listOf(
                row(serverId = "abs-9", itemId = "unrelated", title = "Atomic Habits", author = "James Clear", isbn = "9999999999999"),
            ),
        )
        val links = RecordingReadaloudLinkDao().apply {
            seed(
                ReadaloudLinkEntity(
                    storytellerServerId = "st-1",
                    storytellerBookId = "42",
                    absServerId = "abs-1",
                    absLibraryItemId = "old",
                    state = ReadaloudLinkEntity.STATE_CONFIRMED,
                    userConfirmed = false,
                    createdAt = 1L,
                    updatedAt = 1L,
                ),
            )
        }

        ReadaloudMatchingService(items, links).reconcileLinks()

        assertTrue("auto-link with no current match should be cleared", links.upserts.isEmpty())
        assertEquals(listOf("st-1" to "42"), links.deletions)
    }

    @Test
    fun `empty Storyteller side or empty ABS side writes nothing`() = runTest {
        val itemsNoSt = StubLibraryItemDao(
            storyteller = emptyList(),
            abs = listOf(row(serverId = "abs-1", itemId = "x", isbn = "9780261103573")),
        )
        val itemsNoAbs = StubLibraryItemDao(
            storyteller = listOf(row(serverId = "st-1", itemId = "42", isbn = "9780261103573")),
            abs = emptyList(),
        )
        val a = RecordingReadaloudLinkDao()
        val b = RecordingReadaloudLinkDao()

        ReadaloudMatchingService(itemsNoSt, a).reconcileLinks()
        ReadaloudMatchingService(itemsNoAbs, b).reconcileLinks()

        assertTrue(a.upserts.isEmpty())
        assertTrue(b.upserts.isEmpty())
    }

    private fun row(
        serverId: String,
        itemId: String,
        title: String = "Title",
        author: String = "Author",
        isbn: String? = null,
        asin: String? = null,
    ) = MatchableItemRow(itemId = itemId, serverId = serverId, title = title, author = author, isbn = isbn, asin = asin)

    private class StubLibraryItemDao(
        private val storyteller: List<MatchableItemRow>,
        private val abs: List<MatchableItemRow>,
    ) : LibraryItemDao {
        override suspend fun listMatchableByServerType(serverType: String): List<MatchableItemRow> = when (serverType) {
            "STORYTELLER" -> storyteller
            "AUDIOBOOKSHELF" -> abs
            else -> emptyList()
        }

        override fun observeByLibraryId(libraryId: String): Flow<List<LibraryItemEntity>> = flowOf(emptyList())
        override fun observeUngroupedByLibraryId(libraryId: String): Flow<List<LibraryItemEntity>> = flowOf(emptyList())
        override fun observeInProgress(libraryId: String): Flow<List<LibraryItemEntity>> = flowOf(emptyList())
        override fun observeFinished(libraryId: String): Flow<List<LibraryItemEntity>> = flowOf(emptyList())
        override fun observeRecentlyAdded(libraryId: String): Flow<List<LibraryItemEntity>> = flowOf(emptyList())
        override fun observeAllBooks(libraryId: String): Flow<List<LibraryItemEntity>> = flowOf(emptyList())
        override suspend fun upsertAll(items: List<LibraryItemEntity>) = Unit
        override suspend fun getById(itemId: String): LibraryItemEntity? = null
        override suspend fun deleteByLibraryId(libraryId: String) = Unit
        override suspend fun updateLastOpenedAt(itemId: String, timestamp: Long) = Unit
        override suspend fun updateReadingProgress(itemId: String, progress: Float) = Unit
        override suspend fun getLastOpenedAtMap(libraryId: String): List<LastOpenedAtRow> = emptyList()
        override suspend fun getReadingProgressMap(libraryId: String): List<ReadingProgressRow> = emptyList()
    }

    private class RecordingReadaloudLinkDao : ReadaloudLinkDao {
        val upserts = mutableListOf<ReadaloudLinkEntity>()
        val deletions = mutableListOf<Pair<String, String>>()
        private val store = mutableMapOf<Pair<String, String>, ReadaloudLinkEntity>()

        fun seed(entity: ReadaloudLinkEntity) {
            store[entity.storytellerServerId to entity.storytellerBookId] = entity
        }

        override suspend fun upsert(entity: ReadaloudLinkEntity) {
            upserts += entity
            store[entity.storytellerServerId to entity.storytellerBookId] = entity
        }

        override suspend fun findByStorytellerBook(storytellerServerId: String, storytellerBookId: String): ReadaloudLinkEntity? =
            store[storytellerServerId to storytellerBookId]

        override suspend fun findByAbsItem(absServerId: String, absLibraryItemId: String): ReadaloudLinkEntity? =
            store.values.firstOrNull { it.absServerId == absServerId && it.absLibraryItemId == absLibraryItemId }

        override fun observeAll(): Flow<List<ReadaloudLinkEntity>> = flowOf(store.values.toList())

        override suspend fun countForServer(serverId: String): Int =
            store.values.count { it.storytellerServerId == serverId || it.absServerId == serverId }

        override suspend fun deleteByStorytellerBook(storytellerServerId: String, storytellerBookId: String) {
            deletions += storytellerServerId to storytellerBookId
            store.remove(storytellerServerId to storytellerBookId)
        }
    }
}
