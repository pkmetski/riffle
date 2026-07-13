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
        val keys = links.upserts.map { it.absSourceId to it.absLibraryItemId }.toSet()
        assertEquals(setOf("abs-1" to "ebook", "abs-1" to "audio"), keys)
        for (link in links.upserts) {
            assertEquals("st-1", link.storytellerSourceId)
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
        val keys = links.upserts.map { it.absSourceId to it.absLibraryItemId }.toSet()
        assertEquals(setOf("abs-1" to "books-ebook", "abs-1" to "audiobooks-stub"), keys)
    }

    @Test
    fun `matching is scoped per ABS source so the same title across two servers doesn't double-count`() = runTest {
        // Two ABS logins, each with a Books library + an Audiobooks library containing the
        // same title. With per-source scoping we get one ebook + one audiobook link per
        // source (4 rows across 2 servers), NOT 4 rows on each source.
        val items = StubLibraryItemDao(
            storyteller = listOf(row("st-1", "42", title = "The Martian: A Novel", author = "Andy Weir")),
            abs = listOf(
                row("abs-A", "A-ebook", title = "The Martian", author = "Andy Weir"),
                row("abs-A", "A-audio", title = "The Martian", author = "Andy Weir"),
                row("abs-B", "B-ebook", title = "The Martian", author = "Andy Weir"),
                row("abs-B", "B-audio", title = "The Martian", author = "Andy Weir"),
            ),
        )
        val links = RecordingReadaloudLinkDao()

        service(items, links).reconcileLinks()

        val keys = links.upserts.map { it.absSourceId to it.absLibraryItemId }.toSet()
        assertEquals(
            setOf("abs-A" to "A-ebook", "abs-A" to "A-audio", "abs-B" to "B-ebook", "abs-B" to "B-audio"),
            keys,
        )
        // Each (source, item) is upserted exactly once — no cross-source duplication.
        assertEquals(4, links.upserts.size)
    }

    @Test
    fun `user-confirmed link on one ABS source doesn't suppress auto-matching on another`() = runTest {
        val items = StubLibraryItemDao(
            storyteller = listOf(row("st-1", "42", isbn = "9780261103573")),
            abs = listOf(row("abs-B", "B-ebook", isbn = "9780261103573")),
        )
        val links = RecordingReadaloudLinkDao().apply {
            // User already locked in a slot on source A for this readaloud.
            seed(
                ReadaloudLinkEntity(
                    absSourceId = "abs-A",
                    absLibraryItemId = "A-user-pick",
                    storytellerSourceId = "st-1",
                    storytellerBookId = "42",
                    state = ReadaloudLinkEntity.STATE_CONFIRMED,
                    userConfirmed = true,
                    createdAt = 1L, updatedAt = 1L,
                ),
            )
        }

        service(items, links).reconcileLinks()

        val newLinks = links.upserts.map { it.absSourceId to it.absLibraryItemId }
        assertEquals(listOf("abs-B" to "B-ebook"), newLinks)
        assertTrue("source A user pick must not be swept", links.deletions.none { it == "abs-A" to "A-user-pick" })
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
                    absSourceId = "abs-1",
                    absLibraryItemId = "ebook",
                    storytellerSourceId = "st-1",
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
                    absSourceId = "abs-1",
                    absLibraryItemId = "old",
                    storytellerSourceId = "st-1",
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
        assertEquals("abs-2", n.absSourceId)
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
                    absSourceId = "abs-1",
                    absLibraryItemId = "old",
                    storytellerSourceId = "st-1",
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
        assertEquals("st-1", c.storytellerSourceId)
        assertEquals("42", c.storytellerBookId)
        assertEquals("abs-1", c.absSourceId)
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
                    absSourceId = "abs-1",
                    absLibraryItemId = "user-pick",
                    storytellerSourceId = "st-1",
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
        sourceId: String,
        itemId: String,
        title: String = "Title",
        author: String = "Author",
        isbn: String? = null,
        asin: String? = null,
    ) = MatchableItemRow(itemId, sourceId, title, author, isbn, asin)

    private fun absEntity(
        itemId: String,
        author: String = "Author",
        description: String? = null,
        publishedYear: String? = null,
        publisher: String? = null,
        genres: String = "",
        ebookFormat: String = "epub",
    ) = LibraryItemEntity(
        sourceId = "srv",
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
        addedAt = 0L,
    )

    private class StubLibraryItemDao(
        private val storyteller: List<MatchableItemRow>,
        private val abs: List<MatchableItemRow>,
        entities: List<LibraryItemEntity> = emptyList(),
    ) : LibraryItemDao {
        private val byId = entities.associateBy { it.id }

        override suspend fun listMatchableBySourceType(serverType: String): List<MatchableItemRow> = when (serverType) {
            "STORYTELLER_SERVICE" -> storyteller
            "AUDIOBOOKSHELF" -> abs
            else -> emptyList()
        }
        override fun observeByLibraryId(sourceId: String, libraryId: String): Flow<List<LibraryItemEntity>> = flowOf(emptyList())
        override fun observeUngroupedByLibraryId(sourceId: String, libraryId: String): Flow<List<LibraryItemEntity>> = flowOf(emptyList())
        override fun observeInProgress(sourceId: String, libraryId: String): Flow<List<LibraryItemEntity>> = flowOf(emptyList())
        override fun observeFinished(sourceId: String, libraryId: String): Flow<List<LibraryItemEntity>> = flowOf(emptyList())
        override fun observeRecentlyAdded(sourceId: String, libraryId: String): Flow<List<LibraryItemEntity>> = flowOf(emptyList())
        override fun observeAllBooks(sourceId: String, libraryId: String): Flow<List<LibraryItemEntity>> = flowOf(emptyList())
        override suspend fun upsertAll(items: List<LibraryItemEntity>) = Unit
        override suspend fun insertOrIgnore(items: List<LibraryItemEntity>) = Unit
        override suspend fun updateMetadata(metadata: com.riffle.core.database.LibraryItemMetadata) = Unit
        override suspend fun getById(sourceId: String, itemId: String): LibraryItemEntity? = byId[itemId]
        override suspend fun listByLibraryId(sourceId: String, libraryId: String): List<LibraryItemEntity> = emptyList()
        override suspend fun listByIds(sourceId: String, itemIds: List<String>): List<LibraryItemEntity> {
            val idSet = itemIds.toHashSet()
            return byId.values.filter { it.id in idSet }
        }
        override fun observeById(sourceId: String, itemId: String): Flow<LibraryItemEntity?> = flowOf(byId[itemId])
        override suspend fun findSourceIdForItem(itemId: String): String? = byId[itemId]?.sourceId
        override suspend fun deleteByLibraryId(sourceId: String, libraryId: String) = Unit
        override suspend fun deleteById(sourceId: String, itemId: String) = Unit
        override suspend fun deleteByIds(sourceId: String, itemIds: List<String>) = Unit
        override suspend fun idsForLibrary(sourceId: String, libraryId: String): List<String> = emptyList()
        override suspend fun updateLastOpenedAt(sourceId: String, itemId: String, timestamp: Long) = Unit
        override suspend fun updateReadingProgress(sourceId: String, itemId: String, progress: Float) = Unit
        override suspend fun updateInitialReadingProgress(sourceId: String, itemId: String, progress: Float) = Unit
        override suspend fun updateLibraryId(sourceId: String, itemId: String, libraryId: String) = Unit
        override suspend fun updateFinishedAt(sourceId: String, itemId: String, finishedAt: Long?) = Unit
        override suspend fun getLastOpenedAtMap(sourceId: String, libraryId: String): List<LastOpenedAtRow> = emptyList()
        override suspend fun getReadingProgressMap(sourceId: String, libraryId: String): List<ReadingProgressRow> = emptyList()
        override fun observeBySource(sourceId: String): Flow<List<LibraryItemEntity>> = flowOf(emptyList())
    }

    private class RecordingReadaloudLinkDao : ReadaloudLinkDao {
        override suspend fun updateIdentityResult(absSourceId: String, absLibraryItemId: String, result: String) = Unit
        val upserts = mutableListOf<ReadaloudLinkEntity>()
        val deletions = mutableListOf<Pair<String, String>>()
        private val store = mutableMapOf<Pair<String, String>, ReadaloudLinkEntity>()

        fun seed(entity: ReadaloudLinkEntity) {
            store[entity.absSourceId to entity.absLibraryItemId] = entity
        }

        override suspend fun upsert(entity: ReadaloudLinkEntity) {
            upserts += entity
            store[entity.absSourceId to entity.absLibraryItemId] = entity
        }
        override suspend fun findByAbsItem(absSourceId: String, absLibraryItemId: String): ReadaloudLinkEntity? =
            store[absSourceId to absLibraryItemId]
        override suspend fun findByStorytellerBook(storytellerSourceId: String, storytellerBookId: String): List<ReadaloudLinkEntity> =
            store.values.filter { it.storytellerSourceId == storytellerSourceId && it.storytellerBookId == storytellerBookId }
        override fun observeAll(): Flow<List<ReadaloudLinkEntity>> = flowOf(store.values.toList())
        override suspend fun allRows(): List<ReadaloudLinkEntity> = store.values.toList()
        override fun observeLinkedAbsItemIds(): Flow<List<String>> = flowOf(store.values.map { it.absLibraryItemId })
        override suspend fun countForSource(sourceId: String): Int =
            store.values.count { it.storytellerSourceId == sourceId || it.absSourceId == sourceId }
        override suspend fun deleteByAbsItem(absSourceId: String, absLibraryItemId: String) {
            deletions += absSourceId to absLibraryItemId
            store.remove(absSourceId to absLibraryItemId)
        }
        override suspend fun deleteByStorytellerBook(storytellerSourceId: String, storytellerBookId: String) {
            val toRemove = store.filterValues { it.storytellerSourceId == storytellerSourceId && it.storytellerBookId == storytellerBookId }.keys
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
        override fun observeForStorytellerSource(storytellerSourceId: String): Flow<List<ReadaloudCandidateEntity>> =
            flowOf(store.filter { it.storytellerSourceId == storytellerSourceId })
        override suspend fun deleteByStorytellerBook(storytellerSourceId: String, storytellerBookId: String) {
            store.removeAll { it.storytellerSourceId == storytellerSourceId && it.storytellerBookId == storytellerBookId }
        }
        override suspend fun deleteCandidate(storytellerSourceId: String, storytellerBookId: String, absSourceId: String, absLibraryItemId: String) {
            store.removeAll {
                it.storytellerSourceId == storytellerSourceId && it.storytellerBookId == storytellerBookId &&
                    it.absSourceId == absSourceId && it.absLibraryItemId == absLibraryItemId
            }
        }
    }

    private class RecordingReadaloudDismissalDao : ReadaloudDismissalDao {
        private val store = mutableListOf<ReadaloudDismissalEntity>()

        fun seedBookDismissal(storytellerSourceId: String, storytellerBookId: String) {
            store += ReadaloudDismissalEntity(storytellerSourceId, storytellerBookId, ReadaloudDismissalEntity.SCOPE_BOOK)
        }

        fun seedCandidateDismissal(storytellerSourceId: String, storytellerBookId: String, absSourceId: String, absLibraryItemId: String) {
            store += ReadaloudDismissalEntity(
                storytellerSourceId, storytellerBookId, ReadaloudDismissalEntity.SCOPE_CANDIDATE, absSourceId, absLibraryItemId,
            )
        }

        override suspend fun upsert(entity: ReadaloudDismissalEntity) { store += entity }
        override suspend fun allRows(): List<ReadaloudDismissalEntity> = store.toList()
        override fun observeAll(): Flow<List<ReadaloudDismissalEntity>> = flowOf(store.toList())
        override suspend fun findByStorytellerBook(storytellerSourceId: String, storytellerBookId: String): List<ReadaloudDismissalEntity> =
            store.filter { it.storytellerSourceId == storytellerSourceId && it.storytellerBookId == storytellerBookId }
        override suspend fun isBookDismissed(storytellerSourceId: String, storytellerBookId: String): Boolean =
            store.any {
                it.storytellerSourceId == storytellerSourceId && it.storytellerBookId == storytellerBookId &&
                    it.scope == ReadaloudDismissalEntity.SCOPE_BOOK
            }
        override suspend fun clearBookDismissal(storytellerSourceId: String, storytellerBookId: String) {
            store.removeAll {
                it.storytellerSourceId == storytellerSourceId && it.storytellerBookId == storytellerBookId &&
                    it.scope == ReadaloudDismissalEntity.SCOPE_BOOK
            }
        }
    }
}
