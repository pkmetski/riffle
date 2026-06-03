package com.riffle.core.data

import com.riffle.core.database.LastOpenedAtRow
import com.riffle.core.database.LibraryItemDao
import com.riffle.core.database.LibraryItemEntity
import com.riffle.core.database.MatchableItemRow
import com.riffle.core.database.ReadaloudCandidateDao
import com.riffle.core.database.ReadaloudCandidateEntity
import com.riffle.core.database.ReadaloudDismissalDao
import com.riffle.core.database.ReadaloudDismissalEntity
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

        service(items, links, clock = { 12345L }).reconcileLinks()

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

        service(items, links).reconcileLinks()

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

        service(items, links).reconcileLinks()

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

        service(items, links, clock = { 500L }).reconcileLinks()

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

        service(items, links).reconcileLinks()

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

        service(noSt, a).reconcileLinks()
        service(noAbs, b).reconcileLinks()

        assertTrue(a.upserts.isEmpty())
        assertTrue(b.upserts.isEmpty())
    }

    @Test
    fun `Confirmed link backfills ABS metadata onto the Storyteller item`() = runTest {
        val items = StubLibraryItemDao(
            storyteller = listOf(row("st-1", "42", isbn = "9780261103573")),
            abs = listOf(row("abs-1", "ebook", isbn = "9780261103573")),
            entities = listOf(
                absEntity(
                    "ebook",
                    description = "An epic tale",
                    publishedYear = "1965",
                    publisher = "Chilton",
                    genres = "Sci-Fi,Adventure",
                ),
            ),
        )
        val links = RecordingReadaloudLinkDao()

        service(items, links).reconcileLinks()

        val update = items.metadataUpdates.single { it.itemId == "42" }
        assertEquals("An epic tale", update.description)
        assertEquals("1965", update.publishedYear)
        assertEquals("Chilton", update.publisher)
        assertEquals("Sci-Fi,Adventure", update.genres)
    }

    @Test
    fun `Storyteller item with no link is cleared to empty metadata`() = runTest {
        val items = StubLibraryItemDao(
            storyteller = listOf(row("st-1", "42", title = "Dune", author = "Frank Herbert")),
            abs = listOf(row("abs-9", "unrelated", title = "Atomic Habits", author = "James Clear")),
        )
        val links = RecordingReadaloudLinkDao()

        service(items, links).reconcileLinks()

        val update = items.metadataUpdates.single { it.itemId == "42" }
        assertEquals(null, update.description)
        assertEquals(null, update.publishedYear)
        assertEquals(null, update.publisher)
        assertEquals("", update.genres)
    }

    @Test
    fun `Confirmed link overwrites the Storyteller author with the ABS author`() = runTest {
        // Storyteller's API can return a duplicated/malformed author (e.g. "Andy Weir, Andy Weir;").
        // The clean ABS author should win for matched books.
        val items = StubLibraryItemDao(
            storyteller = listOf(row("st-1", "42", title = "Project Hail Mary", author = "Andy Weir, Andy Weir;", isbn = "9780593135204")),
            abs = listOf(row("abs-1", "ebook", isbn = "9780593135204")),
            entities = listOf(absEntity("ebook", author = "Andy Weir")),
        )
        val links = RecordingReadaloudLinkDao()

        service(items, links).reconcileLinks()

        assertEquals("Andy Weir", items.metadataUpdates.single { it.itemId == "42" }.author)
    }

    @Test
    fun `Storyteller item with no link keeps its own author`() = runTest {
        // A null author in the update means COALESCE leaves the existing (Storyteller) author intact.
        val items = StubLibraryItemDao(
            storyteller = listOf(row("st-1", "42", title = "Dune", author = "Frank Herbert")),
            abs = listOf(row("abs-9", "unrelated", title = "Atomic Habits", author = "James Clear")),
        )
        val links = RecordingReadaloudLinkDao()

        service(items, links).reconcileLinks()

        assertEquals(null, items.metadataUpdates.single { it.itemId == "42" }.author)
    }

    @Test
    fun `metadata is borrowed from the ebook counterpart, not the audiobook stub`() = runTest {
        // The Martian scenario: a readaloud links to both an ebook (rich metadata) and an
        // audiobook stub (unsupported format). The displayed metadata should come from the ebook.
        val items = StubLibraryItemDao(
            storyteller = listOf(row("st-1", "42", title = "The Martian", author = "Andy Weir")),
            abs = listOf(
                // Audiobook stub listed first so a naive first-match would pick it.
                row("abs-1", "audio-stub", title = "The Martian", author = "Andy Weir"),
                row("abs-1", "books-ebook", title = "The Martian", author = "Andy Weir"),
            ),
            entities = listOf(
                absEntity("audio-stub", description = "stub", publisher = "Audio Co", ebookFormat = "unsupported"),
                absEntity("books-ebook", description = "the real synopsis", publisher = "Crown", genres = "Sci-Fi", ebookFormat = "epub"),
            ),
        )
        val links = RecordingReadaloudLinkDao()

        service(items, links).reconcileLinks()

        val update = items.metadataUpdates.single { it.itemId == "42" }
        assertEquals("the real synopsis", update.description)
        assertEquals("Crown", update.publisher)
        assertEquals("Sci-Fi", update.genres)
    }

    // ---- Tier 3 / Pending Review + sticky decisions ---------------------------------------

    @Test
    fun `Tier 3 fuzzy match writes Pending-Review candidates with scores and no link`() = runTest {
        val items = StubLibraryItemDao(
            storyteller = listOf(row("st-1", "42", title = "The Hitchhikers Guide to the Galaxy Part One", author = "Douglas Adams")),
            abs = listOf(row("abs-1", "cand", title = "The Hitchhikers Guide to the Galaxy Part Two", author = "Douglas Adams")),
        )
        val links = RecordingReadaloudLinkDao()
        val candidates = RecordingReadaloudCandidateDao()

        service(items, links, candidates = candidates).reconcileLinks()

        assertTrue("fuzzy match must not auto-confirm a link", links.upserts.isEmpty())
        val c = candidates.rows.single()
        assertEquals("st-1", c.storytellerServerId)
        assertEquals("42", c.storytellerBookId)
        assertEquals("abs-1", c.absServerId)
        assertEquals("cand", c.absLibraryItemId)
        assertTrue("score ${c.score} should be >= threshold", c.score >= 0.85)
    }

    @Test
    fun `per-book don't-ask-again keeps the book Unmatched with no candidates`() = runTest {
        val items = StubLibraryItemDao(
            storyteller = listOf(row("st-1", "42", title = "The Hitchhikers Guide to the Galaxy Part One", author = "Douglas Adams")),
            abs = listOf(row("abs-1", "cand", title = "The Hitchhikers Guide to the Galaxy Part Two", author = "Douglas Adams")),
        )
        val links = RecordingReadaloudLinkDao()
        val candidates = RecordingReadaloudCandidateDao()
        val dismissals = RecordingReadaloudDismissalDao().apply { seedBookDismissal("st-1", "42") }

        service(items, links, candidates = candidates, dismissals = dismissals).reconcileLinks()

        assertTrue(candidates.rows.isEmpty())
        assertTrue(links.upserts.isEmpty())
    }

    @Test
    fun `dismissed candidate is filtered out of Pending Review but others remain`() = runTest {
        val items = StubLibraryItemDao(
            storyteller = listOf(row("st-1", "42", title = "The Hitchhikers Guide to the Galaxy Part One", author = "Douglas Adams")),
            abs = listOf(
                row("abs-1", "cand-a", title = "The Hitchhikers Guide to the Galaxy Part Two", author = "Douglas Adams"),
                row("abs-1", "cand-b", title = "The Hitchhikers Guide to the Galaxy Part Three", author = "Douglas Adams"),
            ),
        )
        val links = RecordingReadaloudLinkDao()
        val candidates = RecordingReadaloudCandidateDao()
        val dismissals = RecordingReadaloudDismissalDao().apply {
            seedCandidateDismissal("st-1", "42", "abs-1", "cand-a")
        }

        service(items, links, candidates = candidates, dismissals = dismissals).reconcileLinks()

        assertEquals(setOf("cand-b"), candidates.rows.map { it.absLibraryItemId }.toSet())
    }

    @Test
    fun `user-Confirmed book is not re-evaluated into Pending Review`() = runTest {
        val items = StubLibraryItemDao(
            storyteller = listOf(row("st-1", "42", title = "The Hitchhikers Guide to the Galaxy Part One", author = "Douglas Adams")),
            abs = listOf(row("abs-1", "fuzzy", title = "The Hitchhikers Guide to the Galaxy Part Two", author = "Douglas Adams")),
        )
        val links = RecordingReadaloudLinkDao().apply {
            seed(
                ReadaloudLinkEntity(
                    absServerId = "abs-1",
                    absLibraryItemId = "user-pick",
                    storytellerServerId = "st-1",
                    storytellerBookId = "42",
                    state = ReadaloudLinkEntity.STATE_CONFIRMED,
                    userConfirmed = true,
                    createdAt = 1L, updatedAt = 1L,
                ),
            )
        }
        val candidates = RecordingReadaloudCandidateDao()

        service(items, links, candidates = candidates).reconcileLinks()

        assertTrue("no candidates for an already user-confirmed book", candidates.rows.isEmpty())
        assertTrue("user link untouched", links.upserts.isEmpty())
        assertTrue("user link not swept", links.deletions.isEmpty())
    }

    @Test
    fun `stale candidates are cleared every pass`() = runTest {
        val items = StubLibraryItemDao(
            storyteller = listOf(row("st-1", "42", title = "Dune", author = "Frank Herbert")),
            abs = listOf(row("abs-9", "unrelated", title = "Atomic Habits", author = "James Clear")),
        )
        val links = RecordingReadaloudLinkDao()
        val candidates = RecordingReadaloudCandidateDao().apply {
            // A candidate left over from a previous pass that no longer fuzzy-matches.
            seed(ReadaloudCandidateEntity("st-1", "42", "abs-9", "stale", 0.9))
        }

        service(items, links, candidates = candidates).reconcileLinks()

        assertTrue("stale candidate must be cleared", candidates.rows.isEmpty())
        assertTrue(candidates.clearAllCalled)
    }

    private fun service(
        items: StubLibraryItemDao,
        links: RecordingReadaloudLinkDao,
        candidates: RecordingReadaloudCandidateDao = RecordingReadaloudCandidateDao(),
        dismissals: RecordingReadaloudDismissalDao = RecordingReadaloudDismissalDao(),
        clock: () -> Long = { 0L },
    ) = ReadaloudMatchingService(items, links, candidates, dismissals, clock)

    private fun row(
        serverId: String,
        itemId: String,
        title: String = "Title",
        author: String = "Author",
        isbn: String? = null,
        asin: String? = null,
    ) = MatchableItemRow(itemId, serverId, title, author, isbn, asin)

    private fun absEntity(
        itemId: String,
        author: String = "Author",
        description: String? = null,
        publishedYear: String? = null,
        publisher: String? = null,
        genres: String = "",
        ebookFormat: String = "epub",
    ) = LibraryItemEntity(
        serverId = "srv",
        id = itemId,
        libraryId = "$itemId-lib",
        title = "Title",
        author = author,
        coverUrl = null,
        readingProgress = 0f,
        ebookFormat = ebookFormat,
        description = description,
        publishedYear = publishedYear,
        publisher = publisher,
        genres = genres,
    )

    data class MetadataUpdate(
        val itemId: String,
        val author: String?,
        val description: String?,
        val publishedYear: String?,
        val publisher: String?,
        val genres: String,
    )

    private class StubLibraryItemDao(
        private val storyteller: List<MatchableItemRow>,
        private val abs: List<MatchableItemRow>,
        entities: List<LibraryItemEntity> = emptyList(),
    ) : LibraryItemDao {
        private val byId = entities.associateBy { it.id }
        val metadataUpdates = mutableListOf<MetadataUpdate>()

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
        override suspend fun getById(serverId: String, itemId: String): LibraryItemEntity? = byId[itemId]
        override suspend fun findServerIdForItem(itemId: String): String? = byId[itemId]?.serverId
        override suspend fun deleteByLibraryId(libraryId: String) = Unit
        override suspend fun updateLastOpenedAt(serverId: String, itemId: String, timestamp: Long) = Unit
        override suspend fun updateReadingProgress(serverId: String, itemId: String, progress: Float) = Unit
        override suspend fun updateReadaloudMetadata(
            serverId: String,
            itemId: String,
            author: String?,
            description: String?,
            publishedYear: String?,
            publisher: String?,
            genres: String,
        ) {
            metadataUpdates += MetadataUpdate(itemId, author, description, publishedYear, publisher, genres)
        }
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

    private class RecordingReadaloudCandidateDao : ReadaloudCandidateDao {
        private val store = mutableListOf<ReadaloudCandidateEntity>()
        var clearAllCalled = false
            private set

        /** Final persisted candidate state after a reconcile pass. */
        val rows: List<ReadaloudCandidateEntity> get() = store.toList()

        fun seed(entity: ReadaloudCandidateEntity) { store += entity }

        override suspend fun upsert(entity: ReadaloudCandidateEntity) { store += entity }
        override suspend fun upsertAll(entities: List<ReadaloudCandidateEntity>) { store += entities }
        override suspend fun allRows(): List<ReadaloudCandidateEntity> = store.toList()
        override suspend fun clearAll() { clearAllCalled = true; store.clear() }
        override fun observeAll(): Flow<List<ReadaloudCandidateEntity>> = flowOf(store.toList())
        override fun observeForStorytellerServer(storytellerServerId: String): Flow<List<ReadaloudCandidateEntity>> =
            flowOf(store.filter { it.storytellerServerId == storytellerServerId })
        override suspend fun findByStorytellerBook(storytellerServerId: String, storytellerBookId: String): List<ReadaloudCandidateEntity> =
            store.filter { it.storytellerServerId == storytellerServerId && it.storytellerBookId == storytellerBookId }
        override suspend fun deleteByStorytellerBook(storytellerServerId: String, storytellerBookId: String) {
            store.removeAll { it.storytellerServerId == storytellerServerId && it.storytellerBookId == storytellerBookId }
        }
        override suspend fun deleteCandidate(storytellerServerId: String, storytellerBookId: String, absServerId: String, absLibraryItemId: String) {
            store.removeAll {
                it.storytellerServerId == storytellerServerId && it.storytellerBookId == storytellerBookId &&
                    it.absServerId == absServerId && it.absLibraryItemId == absLibraryItemId
            }
        }
    }

    private class RecordingReadaloudDismissalDao : ReadaloudDismissalDao {
        private val store = mutableListOf<ReadaloudDismissalEntity>()

        fun seedBookDismissal(storytellerServerId: String, storytellerBookId: String) {
            store += ReadaloudDismissalEntity(storytellerServerId, storytellerBookId, ReadaloudDismissalEntity.SCOPE_BOOK)
        }

        fun seedCandidateDismissal(storytellerServerId: String, storytellerBookId: String, absServerId: String, absLibraryItemId: String) {
            store += ReadaloudDismissalEntity(
                storytellerServerId, storytellerBookId, ReadaloudDismissalEntity.SCOPE_CANDIDATE, absServerId, absLibraryItemId,
            )
        }

        override suspend fun upsert(entity: ReadaloudDismissalEntity) { store += entity }
        override suspend fun allRows(): List<ReadaloudDismissalEntity> = store.toList()
        override fun observeAll(): Flow<List<ReadaloudDismissalEntity>> = flowOf(store.toList())
        override suspend fun findByStorytellerBook(storytellerServerId: String, storytellerBookId: String): List<ReadaloudDismissalEntity> =
            store.filter { it.storytellerServerId == storytellerServerId && it.storytellerBookId == storytellerBookId }
        override suspend fun isBookDismissed(storytellerServerId: String, storytellerBookId: String): Boolean =
            store.any {
                it.storytellerServerId == storytellerServerId && it.storytellerBookId == storytellerBookId &&
                    it.scope == ReadaloudDismissalEntity.SCOPE_BOOK
            }
        override suspend fun clearBookDismissal(storytellerServerId: String, storytellerBookId: String) {
            store.removeAll {
                it.storytellerServerId == storytellerServerId && it.storytellerBookId == storytellerBookId &&
                    it.scope == ReadaloudDismissalEntity.SCOPE_BOOK
            }
        }
    }
}
