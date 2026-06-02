package com.riffle.core.data

import com.riffle.core.database.ReadaloudCandidateDao
import com.riffle.core.database.ReadaloudCandidateEntity
import com.riffle.core.database.ReadaloudDismissalDao
import com.riffle.core.database.ReadaloudDismissalEntity
import com.riffle.core.database.ReadaloudLinkDao
import com.riffle.core.database.ReadaloudLinkEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReadaloudReviewRepositoryTest {

    @Test
    fun `confirmCandidate creates a userConfirmed link and clears the book's candidates`() = runTest {
        val links = RecordingLinkDao()
        val candidates = RecordingCandidateDao().apply {
            seed(ReadaloudCandidateEntity("st-1", "42", "abs-1", "pick", 0.9))
            seed(ReadaloudCandidateEntity("st-1", "42", "abs-1", "other", 0.86))
        }
        val repo = repo(links, candidates)

        repo.confirmCandidate("st-1", "42", "abs-1", "pick")

        val link = links.findByAbsItem("abs-1", "pick")!!
        assertEquals("42", link.storytellerBookId)
        assertTrue("confirmed link must be sticky", link.userConfirmed)
        assertTrue("all of the book's candidates are cleared", candidates.rows.isEmpty())
    }

    @Test
    fun `dismissCandidate persists a candidate-scope dismissal and removes only that candidate`() = runTest {
        val candidates = RecordingCandidateDao().apply {
            seed(ReadaloudCandidateEntity("st-1", "42", "abs-1", "a", 0.9))
            seed(ReadaloudCandidateEntity("st-1", "42", "abs-1", "b", 0.86))
        }
        val dismissals = RecordingDismissalDao()
        val repo = repo(RecordingLinkDao(), candidates, dismissals)

        repo.dismissCandidate("st-1", "42", "abs-1", "a")

        assertEquals(setOf("b"), candidates.rows.map { it.absLibraryItemId }.toSet())
        val d = dismissals.rows.single()
        assertEquals(ReadaloudDismissalEntity.SCOPE_CANDIDATE, d.scope)
        assertEquals("a", d.absLibraryItemId)
    }

    @Test
    fun `dismissBook persists a book-scope dismissal and clears all candidates`() = runTest {
        val candidates = RecordingCandidateDao().apply {
            seed(ReadaloudCandidateEntity("st-1", "42", "abs-1", "a", 0.9))
        }
        val dismissals = RecordingDismissalDao()
        val repo = repo(RecordingLinkDao(), candidates, dismissals)

        repo.dismissBook("st-1", "42")

        assertTrue(candidates.rows.isEmpty())
        assertEquals(ReadaloudDismissalEntity.SCOPE_BOOK, dismissals.rows.single().scope)
        assertTrue(dismissals.isBookDismissed("st-1", "42"))
    }

    @Test
    fun `unlinkBook removes every ABS row paired with the readaloud in one action`() = runTest {
        // Regression: a readaloud links to both an ebook and an audiobook stub. Unlinking the
        // match must detach BOTH so the book returns to Unmatched immediately — not require a
        // second unlink for the audiobook before the "Match manually" button reappears.
        val links = RecordingLinkDao().apply {
            seed(link("abs-1", "ebook", "st-1", "42", userConfirmed = true))
            seed(link("abs-1", "audiobook", "st-1", "42", userConfirmed = true))
        }
        val repo = repo(links, RecordingCandidateDao())

        repo.unlinkBook("st-1", "42")

        assertNull(links.findByAbsItem("abs-1", "ebook"))
        assertNull(links.findByAbsItem("abs-1", "audiobook"))
    }

    @Test
    fun `pairManually can attach several ABS items to one readaloud`() = runTest {
        // A readaloud links to both an ebook and an audiobook. Picking the second must NOT
        // remove the first — the picker stays open so the user can attach more than one.
        val links = RecordingLinkDao()
        val repo = repo(links, RecordingCandidateDao())

        repo.pairManually("st-1", "42", "abs-1", "ebook")
        repo.pairManually("st-1", "42", "abs-1", "audiobook")

        assertEquals(
            setOf("ebook", "audiobook"),
            links.findByStorytellerBook("st-1", "42").map { it.absLibraryItemId }.toSet(),
        )
    }

    @Test
    fun `unlinkAbsItem detaches one ABS item and leaves siblings linked`() = runTest {
        val links = RecordingLinkDao().apply {
            seed(link("abs-1", "ebook", "st-1", "42", userConfirmed = true))
            seed(link("abs-1", "audiobook", "st-1", "42", userConfirmed = true))
        }
        val repo = repo(links, RecordingCandidateDao())

        repo.unlinkAbsItem("abs-1", "ebook")

        assertNull(links.findByAbsItem("abs-1", "ebook"))
        assertEquals(
            setOf("audiobook"),
            links.findByStorytellerBook("st-1", "42").map { it.absLibraryItemId }.toSet(),
        )
    }

    @Test
    fun `pairManually creates a sticky link and clears prior don't-ask-again`() = runTest {
        val links = RecordingLinkDao()
        val candidates = RecordingCandidateDao()
        val dismissals = RecordingDismissalDao().apply {
            store += ReadaloudDismissalEntity("st-1", "42", ReadaloudDismissalEntity.SCOPE_BOOK)
        }
        val repo = repo(links, candidates, dismissals)

        repo.pairManually("st-1", "42", "abs-2", "chosen")

        val link = links.findByAbsItem("abs-2", "chosen")!!
        assertTrue(link.userConfirmed)
        assertTrue("manual pairing overrides 'don't ask again'", !dismissals.isBookDismissed("st-1", "42"))
    }

    private fun repo(
        links: RecordingLinkDao,
        candidates: RecordingCandidateDao,
        dismissals: RecordingDismissalDao = RecordingDismissalDao(),
    ) = ReadaloudReviewRepositoryImpl(
        libraryItemDao = ThrowingLibraryItemDao,
        libraryDao = ThrowingLibraryDao,
        linkDao = links,
        candidateDao = candidates,
        dismissalDao = dismissals,
        clock = { 1000L },
    )

    private fun link(
        absServerId: String,
        absLibraryItemId: String,
        storytellerServerId: String,
        storytellerBookId: String,
        userConfirmed: Boolean,
    ) = ReadaloudLinkEntity(
        absServerId, absLibraryItemId, storytellerServerId, storytellerBookId,
        ReadaloudLinkEntity.STATE_CONFIRMED, userConfirmed, 1L, 1L,
    )

    private class RecordingLinkDao : ReadaloudLinkDao {
        private val store = mutableMapOf<Pair<String, String>, ReadaloudLinkEntity>()
        fun seed(e: ReadaloudLinkEntity) { store[e.absServerId to e.absLibraryItemId] = e }
        override suspend fun upsert(entity: ReadaloudLinkEntity) { store[entity.absServerId to entity.absLibraryItemId] = entity }
        override suspend fun findByAbsItem(absServerId: String, absLibraryItemId: String) = store[absServerId to absLibraryItemId]
        override suspend fun findByStorytellerBook(storytellerServerId: String, storytellerBookId: String) =
            store.values.filter { it.storytellerServerId == storytellerServerId && it.storytellerBookId == storytellerBookId }
        override fun observeAll(): Flow<List<ReadaloudLinkEntity>> = flowOf(store.values.toList())
        override suspend fun allRows() = store.values.toList()
        override fun observeLinkedAbsItemIds(): Flow<List<String>> = flowOf(store.values.map { it.absLibraryItemId })
        override fun observeLinkedStorytellerBookIds(): Flow<List<String>> = flowOf(store.values.map { it.storytellerBookId })
        override suspend fun countForServer(serverId: String) = 0
        override suspend fun deleteByAbsItem(absServerId: String, absLibraryItemId: String) { store.remove(absServerId to absLibraryItemId) }
        override suspend fun deleteByStorytellerBook(storytellerServerId: String, storytellerBookId: String) {
            store.values.filter { it.storytellerServerId == storytellerServerId && it.storytellerBookId == storytellerBookId }
                .forEach { store.remove(it.absServerId to it.absLibraryItemId) }
        }
    }

    private class RecordingCandidateDao : ReadaloudCandidateDao {
        private val store = mutableListOf<ReadaloudCandidateEntity>()
        val rows: List<ReadaloudCandidateEntity> get() = store.toList()
        fun seed(e: ReadaloudCandidateEntity) { store += e }
        override suspend fun upsert(entity: ReadaloudCandidateEntity) { store += entity }
        override suspend fun upsertAll(entities: List<ReadaloudCandidateEntity>) { store += entities }
        override suspend fun allRows() = store.toList()
        override suspend fun clearAll() { store.clear() }
        override fun observeAll(): Flow<List<ReadaloudCandidateEntity>> = flowOf(store.toList())
        override fun observeForStorytellerServer(storytellerServerId: String): Flow<List<ReadaloudCandidateEntity>> =
            flowOf(store.filter { it.storytellerServerId == storytellerServerId })
        override suspend fun findByStorytellerBook(storytellerServerId: String, storytellerBookId: String) =
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

    private class RecordingDismissalDao : ReadaloudDismissalDao {
        val store = mutableListOf<ReadaloudDismissalEntity>()
        val rows: List<ReadaloudDismissalEntity> get() = store.toList()
        override suspend fun upsert(entity: ReadaloudDismissalEntity) { store += entity }
        override suspend fun allRows() = store.toList()
        override fun observeAll(): Flow<List<ReadaloudDismissalEntity>> = flowOf(store.toList())
        override suspend fun findByStorytellerBook(storytellerServerId: String, storytellerBookId: String) =
            store.filter { it.storytellerServerId == storytellerServerId && it.storytellerBookId == storytellerBookId }
        override suspend fun isBookDismissed(storytellerServerId: String, storytellerBookId: String) =
            store.any { it.storytellerServerId == storytellerServerId && it.storytellerBookId == storytellerBookId && it.scope == ReadaloudDismissalEntity.SCOPE_BOOK }
        override suspend fun clearBookDismissal(storytellerServerId: String, storytellerBookId: String) {
            store.removeAll { it.storytellerServerId == storytellerServerId && it.storytellerBookId == storytellerBookId && it.scope == ReadaloudDismissalEntity.SCOPE_BOOK }
        }
    }
}
