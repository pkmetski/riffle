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
import org.junit.Assert.assertTrue
import org.junit.Test

class ReadaloudMatchingServiceTest {

    @Test
    fun `Tier 1 ISBN match writes one row per ABS candidate`() = runTest {
        // 1:many semantics — the same readaloud links to every ABS row sharing the ISBN.
        val items = StubLibraryItemDao(
            storyteller = listOf(row("st-1", "42", isbn = "9780261103573")),
            abs = listOf(
                row("abs-1", "ebook", isbn = "9780261103573"),
                row("abs-1", "audio", isbn = "9780261103573"),
            ),
        )
        val links = RecordingReadaloudLinkDao()

        ReadaloudMatchingService(items, links, clock = { 12345L }).reconcileLinks()

        assertEquals(2, links.upserts.size)
        val keys = links.upserts.map { it.absServerId to it.absLibraryItemId }.toSet()
        assertEquals(setOf("abs-1" to "ebook", "abs-1" to "audio"), keys)
        for (link in links.upserts) {
            assertEquals("st-1", link.storytellerServerId)
            assertEquals("42", link.storytellerBookId)
            assertEquals(false, link.userConfirmed)
        }
    }

    @Test
    fun `Tier 2 title and author match writes one row per ABS candidate`() = runTest {
        // The Martian scenario from the user's real data: ebook in Books library and an
        // audiobook stub in an Audiobooks library, both sharing title+author with the
        // Storyteller readaloud.
        val items = StubLibraryItemDao(
            storyteller = listOf(row("st-1", "42", title = "The Martian: A Novel", author = "Andy Weir")),
            abs = listOf(
                row("abs-1", "books-ebook", title = "The Martian", author = "Andy Weir"),
                row("abs-1", "audiobooks-stub", title = "The Martian", author = "Andy Weir"),
            ),
        )
        val links = RecordingReadaloudLinkDao()

        ReadaloudMatchingService(items, links).reconcileLinks()

        assertEquals(2, links.upserts.size)
        val keys = links.upserts.map { it.absServerId to it.absLibraryItemId }.toSet()
        assertEquals(setOf("abs-1" to "books-ebook", "abs-1" to "audiobooks-stub"), keys)
    }

    @Test
    fun `userConfirmed row in an ABS slot is never overwritten by the auto-matcher`() = runTest {
        val items = StubLibraryItemDao(
            storyteller = listOf(row("st-1", "42", isbn = "9780261103573")),
            abs = listOf(row("abs-1", "ebook", isbn = "9780261103573")),
        )
        val links = RecordingReadaloudLinkDao().apply {
            seed(
                ReadaloudLinkEntity(
                    absServerId = "abs-1",
                    absLibraryItemId = "ebook",
                    storytellerServerId = "st-1",
                    storytellerBookId = "different-book",
                    state = ReadaloudLinkEntity.STATE_CONFIRMED,
                    userConfirmed = true,
                    createdAt = 1L, updatedAt = 1L,
                ),
            )
        }

        ReadaloudMatchingService(items, links).reconcileLinks()

        assertTrue("userConfirmed row stays untouched", links.upserts.isEmpty())
        assertTrue("userConfirmed row not deleted", links.deletions.isEmpty())
    }

    @Test
    fun `auto-Confirmed row is rewritten when matcher verdict moves`() = runTest {
        val items = StubLibraryItemDao(
            storyteller = listOf(row("st-1", "42", isbn = "9780261103573")),
            abs = listOf(row("abs-2", "moved", isbn = "9780261103573")),
        )
        val links = RecordingReadaloudLinkDao().apply {
            seed(
                ReadaloudLinkEntity(
                    absServerId = "abs-1",
                    absLibraryItemId = "old",
                    storytellerServerId = "st-1",
                    storytellerBookId = "42",
                    state = ReadaloudLinkEntity.STATE_CONFIRMED,
                    userConfirmed = false,
                    createdAt = 100L, updatedAt = 100L,
                ),
            )
        }

        ReadaloudMatchingService(items, links, clock = { 500L }).reconcileLinks()

        // The old auto row gets swept; the new ABS slot is upserted.
        assertEquals(1, links.upserts.size)
        val n = links.upserts.single()
        assertEquals("abs-2", n.absServerId)
        assertEquals("moved", n.absLibraryItemId)
        assertEquals(listOf("abs-1" to "old"), links.deletions)
    }

    @Test
    fun `stale auto-Confirmed row whose match disappears is deleted`() = runTest {
        val items = StubLibraryItemDao(
            storyteller = listOf(row("st-1", "42", title = "Dune", author = "Frank Herbert", isbn = "9780261103573")),
            abs = listOf(row("abs-9", "unrelated", title = "Atomic Habits", author = "James Clear", isbn = "9999999999999")),
        )
        val links = RecordingReadaloudLinkDao().apply {
            seed(
                ReadaloudLinkEntity(
                    absServerId = "abs-1",
                    absLibraryItemId = "old",
                    storytellerServerId = "st-1",
                    storytellerBookId = "42",
                    state = ReadaloudLinkEntity.STATE_CONFIRMED,
                    userConfirmed = false,
                    createdAt = 1L, updatedAt = 1L,
                ),
            )
        }

        ReadaloudMatchingService(items, links).reconcileLinks()

        assertTrue(links.upserts.isEmpty())
        assertEquals(listOf("abs-1" to "old"), links.deletions)
    }

    @Test
    fun `empty side writes nothing`() = runTest {
        val noSt = StubLibraryItemDao(
            storyteller = emptyList(),
            abs = listOf(row("abs-1", "x", isbn = "9780261103573")),
        )
        val noAbs = StubLibraryItemDao(
            storyteller = listOf(row("st-1", "42", isbn = "9780261103573")),
            abs = emptyList(),
        )
        val a = RecordingReadaloudLinkDao()
        val b = RecordingReadaloudLinkDao()

        ReadaloudMatchingService(noSt, a).reconcileLinks()
        ReadaloudMatchingService(noAbs, b).reconcileLinks()

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
    ) = MatchableItemRow(itemId, serverId, title, author, isbn, asin)

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
            store[entity.absServerId to entity.absLibraryItemId] = entity
        }

        override suspend fun upsert(entity: ReadaloudLinkEntity) {
            upserts += entity
            store[entity.absServerId to entity.absLibraryItemId] = entity
        }
        override suspend fun findByAbsItem(absServerId: String, absLibraryItemId: String): ReadaloudLinkEntity? =
            store[absServerId to absLibraryItemId]
        override suspend fun findByStorytellerBook(storytellerServerId: String, storytellerBookId: String): List<ReadaloudLinkEntity> =
            store.values.filter { it.storytellerServerId == storytellerServerId && it.storytellerBookId == storytellerBookId }
        override fun observeAll(): Flow<List<ReadaloudLinkEntity>> = flowOf(store.values.toList())
        override suspend fun allRows(): List<ReadaloudLinkEntity> = store.values.toList()
        override fun observeLinkedAbsItemIds(): Flow<List<String>> = flowOf(store.values.map { it.absLibraryItemId })
        override fun observeLinkedStorytellerBookIds(): Flow<List<String>> = flowOf(store.values.map { it.storytellerBookId })
        override suspend fun countForServer(serverId: String): Int =
            store.values.count { it.storytellerServerId == serverId || it.absServerId == serverId }
        override suspend fun deleteByAbsItem(absServerId: String, absLibraryItemId: String) {
            deletions += absServerId to absLibraryItemId
            store.remove(absServerId to absLibraryItemId)
        }
        override suspend fun deleteByStorytellerBook(storytellerServerId: String, storytellerBookId: String) {
            val toRemove = store.filterValues { it.storytellerServerId == storytellerServerId && it.storytellerBookId == storytellerBookId }.keys
            deletions += toRemove
            toRemove.forEach { store.remove(it) }
        }
    }
}
