package com.riffle.core.database.dao

import com.riffle.core.database.CrossEpubIndexEntity
import com.riffle.core.database.ReadaloudCandidateEntity
import com.riffle.core.database.ReadaloudDismissalEntity
import com.riffle.core.database.ReadaloudLinkEntity
import com.riffle.core.database.ReadaloudResumePositionEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Behavioural coverage for the readaloud-matching DAOs on iOS (`IosReadaloudLinkDao`,
 * `IosReadaloudCandidateDao`, `IosReadaloudDismissalDao`, `IosReadaloudResumePositionDao`,
 * `IosCrossEpubIndexDao`) — the iOS halves of the tables ADR 0025 defines.
 *
 * `IosRiffleDatabaseSchemaTest` already pins the single-row insert/read/delete shape of each of
 * these. What it does not pin, and what actually breaks on device, is the query *semantics*:
 * which rows a filtered query includes, whether a scoped delete takes its neighbours with it,
 * whether nullable columns and schema defaults survive the round trip, and whether a live Flow
 * subscriber is told about a write at all.
 */
class IosReadaloudDaoTest : IosDaoTestBase() {

    private val storytellerBookId = "st-book-1"
    private val otherStorytellerBookId = "st-book-2"

    private fun link(
        absLibraryItemId: String,
        storytellerBookId: String = this.storytellerBookId,
        absSourceId: String = SOURCE_ID,
        storytellerSourceId: String = OTHER_SOURCE_ID,
        userConfirmed: Boolean = true,
        createdAt: Long = 1_000L,
        updatedAt: Long = 1_000L,
    ) = ReadaloudLinkEntity(
        absSourceId = absSourceId,
        absLibraryItemId = absLibraryItemId,
        storytellerSourceId = storytellerSourceId,
        storytellerBookId = storytellerBookId,
        userConfirmed = userConfirmed,
        createdAt = createdAt,
        updatedAt = updatedAt,
    )

    // ── IosReadaloudLinkDao ───────────────────────────────────────────────────

    @Test
    fun readaloudLinkAppliesDefaultStateAndIdentityResultOnRoundTrip() = runTest {
        seedSource(SOURCE_ID)
        seedSource(OTHER_SOURCE_ID)
        val dao = db.readaloudLinkDao()

        dao.upsert(link(ITEM_ID))

        val stored = dao.findByAbsItem(SOURCE_ID, ITEM_ID)
        assertNotNull(stored, "Link must be readable after upsert")
        assertEquals(
            ReadaloudLinkEntity.STATE_CONFIRMED,
            stored.state,
            "A link written without an explicit state must read back as CONFIRMED",
        )
        assertEquals(
            ReadaloudLinkEntity.IDENTITY_UNKNOWN,
            stored.identityResult,
            "A link written before any streaming identity check must read back as UNKNOWN",
        )
    }

    @Test
    fun readaloudLinkFindByStorytellerBookReturnsEveryLinkedAbsItem() = runTest {
        seedSource(SOURCE_ID)
        seedSource(OTHER_SOURCE_ID)
        val dao = db.readaloudLinkDao()
        // One readaloud, two ABS items: the ebook in a Books library and the audiobook stub in an
        // Audiobooks library. ReadaloudLinkEntity documents this one-to-many explicitly.
        dao.upsert(link(ITEM_ID))
        dao.upsert(link(OTHER_ITEM_ID))
        dao.upsert(link("item-3", storytellerBookId = otherStorytellerBookId))

        val matched = dao.findByStorytellerBook(OTHER_SOURCE_ID, storytellerBookId)

        assertEquals(
            listOf(ITEM_ID, OTHER_ITEM_ID),
            matched.map { it.absLibraryItemId }.sorted(),
            "A readaloud must resolve to every ABS item linked to it",
        )
    }

    @Test
    fun readaloudLinkDeleteByAbsItemLeavesSiblingLinksIntact() = runTest {
        seedSource(SOURCE_ID)
        seedSource(OTHER_SOURCE_ID)
        val dao = db.readaloudLinkDao()
        dao.upsert(link(ITEM_ID))
        dao.upsert(link(OTHER_ITEM_ID))

        dao.deleteByAbsItem(SOURCE_ID, ITEM_ID)

        assertNull(dao.findByAbsItem(SOURCE_ID, ITEM_ID), "Targeted link must be gone")
        assertNotNull(
            dao.findByAbsItem(SOURCE_ID, OTHER_ITEM_ID),
            "Unlinking one ABS item must not unlink the readaloud's other ABS items",
        )
    }

    @Test
    fun readaloudLinkDeleteByStorytellerBookRemovesEveryLinkedAbsItem() = runTest {
        seedSource(SOURCE_ID)
        seedSource(OTHER_SOURCE_ID)
        val dao = db.readaloudLinkDao()
        dao.upsert(link(ITEM_ID))
        dao.upsert(link(OTHER_ITEM_ID))
        dao.upsert(link("item-3", storytellerBookId = otherStorytellerBookId))

        dao.deleteByStorytellerBook(OTHER_SOURCE_ID, storytellerBookId)

        assertEquals(
            listOf("item-3"),
            dao.allRows().map { it.absLibraryItemId },
            "Deleting a readaloud must take all of its ABS links, and only those",
        )
    }

    @Test
    fun readaloudLinkCountForSourceCountsAbsAndStorytellerSidesTogether() = runTest {
        seedSource(SOURCE_ID)
        seedSource(OTHER_SOURCE_ID)
        val dao = db.readaloudLinkDao()
        // Two rows where SOURCE_ID is the ABS side …
        dao.upsert(link(ITEM_ID))
        dao.upsert(link(OTHER_ITEM_ID))
        // … and one where it is the Storyteller side. countForSource ORs both columns, so a source
        // that is only ever the narration provider still reports its links.
        dao.upsert(
            link("item-3", absSourceId = OTHER_SOURCE_ID, storytellerSourceId = SOURCE_ID),
        )

        assertEquals(3, dao.countForSource(SOURCE_ID))
        assertEquals(3, dao.countForSource(OTHER_SOURCE_ID))
    }

    @Test
    fun readaloudLinkUpsertReplacesTheRowWithTheSameAbsPrimaryKey() = runTest {
        seedSource(SOURCE_ID)
        seedSource(OTHER_SOURCE_ID)
        val dao = db.readaloudLinkDao()
        dao.upsert(link(ITEM_ID, userConfirmed = false, updatedAt = 1_000L))

        dao.upsert(link(ITEM_ID, userConfirmed = true, updatedAt = 2_000L))

        assertEquals(1, dao.allRows().size, "Re-upserting the same ABS item must not duplicate the link")
        val stored = dao.findByAbsItem(SOURCE_ID, ITEM_ID)
        assertNotNull(stored)
        assertTrue(stored.userConfirmed, "The replacing row's userConfirmed must win")
        assertEquals(2_000L, stored.updatedAt)
    }

    @Test
    fun readaloudLinkUpdateIdentityResultTouchesOnlyTheTargetedLink() = runTest {
        seedSource(SOURCE_ID)
        seedSource(OTHER_SOURCE_ID)
        val dao = db.readaloudLinkDao()
        dao.upsert(link(ITEM_ID))
        dao.upsert(link(OTHER_ITEM_ID))

        dao.updateIdentityResult(SOURCE_ID, ITEM_ID, "VERIFIED")

        assertEquals("VERIFIED", dao.findByAbsItem(SOURCE_ID, ITEM_ID)?.identityResult)
        assertEquals(
            ReadaloudLinkEntity.IDENTITY_UNKNOWN,
            dao.findByAbsItem(SOURCE_ID, OTHER_ITEM_ID)?.identityResult,
            "Verifying one ABS item must not mark its sibling verified",
        )
    }

    @Test
    fun readaloudLinkObserveAllEmitsAgainOnWriteAndOnDelete() = runTest {
        seedSource(SOURCE_ID)
        seedSource(OTHER_SOURCE_ID)
        val dao = db.readaloudLinkDao()
        val emissions = recordEmissions(dao.observeAll())
        assertEquals(1, emissions.size, "A new subscriber must get the current contents immediately")
        assertEquals(emptyList<ReadaloudLinkEntity>(), emissions.last())

        dao.upsert(link(ITEM_ID))
        settleEmissions()

        assertEquals(2, emissions.size, "An upsert must push a new value to an existing subscriber")
        assertEquals(listOf(ITEM_ID), emissions.last().map { it.absLibraryItemId })

        dao.deleteByAbsItem(SOURCE_ID, ITEM_ID)
        settleEmissions()

        assertEquals(3, emissions.size, "A delete must push a new value to an existing subscriber")
        assertEquals(emptyList<ReadaloudLinkEntity>(), emissions.last())
    }

    @Test
    fun readaloudLinkObserveLinkedAbsItemIdsEmitsAgainOnWrite() = runTest {
        seedSource(SOURCE_ID)
        seedSource(OTHER_SOURCE_ID)
        val dao = db.readaloudLinkDao()
        val emissions = recordEmissions(dao.observeLinkedAbsItemIds())
        assertEquals(1, emissions.size, "A new subscriber must get the current contents immediately")
        assertEquals(emptyList<String>(), emissions.last())

        dao.upsert(link(ITEM_ID))
        settleEmissions()

        assertEquals(2, emissions.size, "Linking a book must refresh the linked-item-id projection")
        assertEquals(listOf(ITEM_ID), emissions.last())
    }

    // ── IosReadaloudCandidateDao ──────────────────────────────────────────────

    private fun candidate(
        absLibraryItemId: String,
        storytellerBookId: String = this.storytellerBookId,
        storytellerSourceId: String = OTHER_SOURCE_ID,
        score: Double = 0.75,
    ) = ReadaloudCandidateEntity(
        storytellerSourceId = storytellerSourceId,
        storytellerBookId = storytellerBookId,
        absSourceId = SOURCE_ID,
        absLibraryItemId = absLibraryItemId,
        score = score,
    )

    @Test
    fun readaloudCandidateUpsertAllPersistsEveryRow() = runTest {
        seedSource(SOURCE_ID)
        seedSource(OTHER_SOURCE_ID)
        val dao = db.readaloudCandidateDao()

        dao.upsertAll(listOf(candidate(ITEM_ID, score = 0.9), candidate(OTHER_ITEM_ID, score = 0.6)))

        assertEquals(
            mapOf(ITEM_ID to 0.9, OTHER_ITEM_ID to 0.6),
            dao.allRows().associate { it.absLibraryItemId to it.score },
            "upsertAll must write every candidate with its own score, not just the first",
        )
    }

    @Test
    fun readaloudCandidateDeleteCandidateRemovesOnlyTheTargetedPair() = runTest {
        seedSource(SOURCE_ID)
        seedSource(OTHER_SOURCE_ID)
        val dao = db.readaloudCandidateDao()
        dao.upsertAll(listOf(candidate(ITEM_ID), candidate(OTHER_ITEM_ID)))

        dao.deleteCandidate(OTHER_SOURCE_ID, storytellerBookId, SOURCE_ID, ITEM_ID)

        assertEquals(
            listOf(OTHER_ITEM_ID),
            dao.allRows().map { it.absLibraryItemId },
            "Dismissing one candidate must leave the readaloud's other candidates in Pending Review",
        )
    }

    @Test
    fun readaloudCandidateDeleteByStorytellerBookRemovesOnlyThatBooksCandidates() = runTest {
        seedSource(SOURCE_ID)
        seedSource(OTHER_SOURCE_ID)
        val dao = db.readaloudCandidateDao()
        dao.upsertAll(
            listOf(
                candidate(ITEM_ID),
                candidate(OTHER_ITEM_ID),
                candidate("item-3", storytellerBookId = otherStorytellerBookId),
            ),
        )

        dao.deleteByStorytellerBook(OTHER_SOURCE_ID, storytellerBookId)

        assertEquals(listOf("item-3"), dao.allRows().map { it.absLibraryItemId })
    }

    @Test
    fun readaloudCandidateObserveForStorytellerSourceFiltersBySource() = runTest {
        seedSource(SOURCE_ID)
        seedSource(OTHER_SOURCE_ID)
        val dao = db.readaloudCandidateDao()
        dao.upsertAll(
            listOf(
                candidate(ITEM_ID),
                candidate(OTHER_ITEM_ID, storytellerSourceId = SOURCE_ID),
            ),
        )

        assertEquals(
            listOf(ITEM_ID),
            dao.observeForStorytellerSource(OTHER_SOURCE_ID).first().map { it.absLibraryItemId },
        )
        assertEquals(
            listOf(OTHER_ITEM_ID),
            dao.observeForStorytellerSource(SOURCE_ID).first().map { it.absLibraryItemId },
        )
    }

    @Test
    fun readaloudCandidateObserveAllEmitsAgainOnWriteAndOnClearAll() = runTest {
        seedSource(SOURCE_ID)
        seedSource(OTHER_SOURCE_ID)
        val dao = db.readaloudCandidateDao()
        val emissions = recordEmissions(dao.observeAll())
        assertEquals(1, emissions.size)

        dao.upsert(candidate(ITEM_ID))
        settleEmissions()
        assertEquals(2, emissions.size, "upsert must notify live subscribers")
        assertEquals(1, emissions.last().size)

        dao.clearAll()
        settleEmissions()
        assertEquals(3, emissions.size, "clearAll must notify live subscribers")
        assertEquals(emptyList<ReadaloudCandidateEntity>(), emissions.last())
    }

    @Test
    fun readaloudCandidateUpsertAllOnAnEmptyListDoesNotNotifySubscribers() = runTest {
        seedSource(SOURCE_ID)
        seedSource(OTHER_SOURCE_ID)
        val dao = db.readaloudCandidateDao()
        val emissions = recordEmissions(dao.observeAll())

        dao.upsertAll(emptyList())
        settleEmissions()

        assertEquals(
            1,
            emissions.size,
            "A no-op batch must not invalidate — every other DAO's collectors share the invalidator",
        )
    }

    // ── IosReadaloudDismissalDao ──────────────────────────────────────────────

    @Test
    fun readaloudDismissalBookScopeStoresEmptyAbsIdentifiers() = runTest {
        seedSource(OTHER_SOURCE_ID)
        val dao = db.readaloudDismissalDao()

        dao.upsert(
            ReadaloudDismissalEntity(
                storytellerSourceId = OTHER_SOURCE_ID,
                storytellerBookId = storytellerBookId,
                scope = ReadaloudDismissalEntity.SCOPE_BOOK,
            ),
        )

        val stored = dao.allRows().single()
        // Empty strings, not NULL: the four-column primary key has no nullable member, and the
        // mapper's `?: ""` fallback must never turn a book-scope row into a bogus candidate row.
        assertEquals("", stored.absSourceId)
        assertEquals("", stored.absLibraryItemId)
        assertEquals(ReadaloudDismissalEntity.SCOPE_BOOK, stored.scope)
    }

    @Test
    fun readaloudDismissalIsBookDismissedIgnoresCandidateScopeRows() = runTest {
        seedSource(SOURCE_ID)
        seedSource(OTHER_SOURCE_ID)
        val dao = db.readaloudDismissalDao()
        dao.upsert(
            ReadaloudDismissalEntity(
                storytellerSourceId = OTHER_SOURCE_ID,
                storytellerBookId = storytellerBookId,
                scope = ReadaloudDismissalEntity.SCOPE_CANDIDATE,
                absSourceId = SOURCE_ID,
                absLibraryItemId = ITEM_ID,
            ),
        )

        assertFalse(
            dao.isBookDismissed(OTHER_SOURCE_ID, storytellerBookId),
            "Dismissing a single candidate must not move the whole readaloud to Unmatched",
        )

        dao.upsert(
            ReadaloudDismissalEntity(
                storytellerSourceId = OTHER_SOURCE_ID,
                storytellerBookId = storytellerBookId,
                scope = ReadaloudDismissalEntity.SCOPE_BOOK,
            ),
        )

        assertTrue(dao.isBookDismissed(OTHER_SOURCE_ID, storytellerBookId))
    }

    @Test
    fun readaloudDismissalClearBookDismissalKeepsCandidateScopeRows() = runTest {
        seedSource(SOURCE_ID)
        seedSource(OTHER_SOURCE_ID)
        val dao = db.readaloudDismissalDao()
        dao.upsert(
            ReadaloudDismissalEntity(
                storytellerSourceId = OTHER_SOURCE_ID,
                storytellerBookId = storytellerBookId,
                scope = ReadaloudDismissalEntity.SCOPE_BOOK,
            ),
        )
        dao.upsert(
            ReadaloudDismissalEntity(
                storytellerSourceId = OTHER_SOURCE_ID,
                storytellerBookId = storytellerBookId,
                scope = ReadaloudDismissalEntity.SCOPE_CANDIDATE,
                absSourceId = SOURCE_ID,
                absLibraryItemId = ITEM_ID,
            ),
        )

        dao.clearBookDismissal(OTHER_SOURCE_ID, storytellerBookId)

        assertEquals(
            listOf(ReadaloudDismissalEntity.SCOPE_CANDIDATE),
            dao.findByStorytellerBook(OTHER_SOURCE_ID, storytellerBookId).map { it.scope },
            "Re-opening a book for matching must not resurrect individually dismissed candidates",
        )
    }

    @Test
    fun readaloudDismissalObserveAllEmitsAgainOnWrite() = runTest {
        seedSource(OTHER_SOURCE_ID)
        val dao = db.readaloudDismissalDao()
        val emissions = recordEmissions(dao.observeAll())
        assertEquals(1, emissions.size, "A new subscriber must get the current contents immediately")
        assertEquals(emptyList<ReadaloudDismissalEntity>(), emissions.last())

        dao.upsert(
            ReadaloudDismissalEntity(
                storytellerSourceId = OTHER_SOURCE_ID,
                storytellerBookId = storytellerBookId,
                scope = ReadaloudDismissalEntity.SCOPE_BOOK,
            ),
        )
        settleEmissions()

        assertEquals(2, emissions.size)
        assertEquals(1, emissions.last().size)
    }

    // ── IosCrossEpubIndexDao ──────────────────────────────────────────────────

    @Test
    fun crossEpubIndexFindMissesWhenEitherChecksumChanges() = runTest {
        val dao = db.crossEpubIndexDao()
        val entity = CrossEpubIndexEntity(
            absEpubChecksum = "abs-v1",
            storytellerEpubChecksum = "st-v1",
            perChapterMapsBlob = "{\"0\":[1,2]}",
            builtAt = 1_000L,
        )
        dao.upsert(entity)

        assertEquals(entity, dao.find("abs-v1", "st-v1"))
        // ADR 0023: the cache needs no explicit invalidation because a re-uploaded EPUB changes its
        // checksum and the keyed lookup simply misses.
        assertNull(dao.find("abs-v2", "st-v1"), "A re-uploaded ABS EPUB must miss the cache")
        assertNull(dao.find("abs-v1", "st-v2"), "A re-uploaded Storyteller EPUB must miss the cache")
    }

    @Test
    fun crossEpubIndexUpsertReplacesTheBlobForTheSameChecksumPair() = runTest {
        val dao = db.crossEpubIndexDao()
        dao.upsert(CrossEpubIndexEntity("abs-v1", "st-v1", "{\"0\":[1]}", 1_000L))

        dao.upsert(CrossEpubIndexEntity("abs-v1", "st-v1", "{\"0\":[9]}", 2_000L))

        val stored = dao.find("abs-v1", "st-v1")
        assertNotNull(stored)
        assertEquals("{\"0\":[9]}", stored.perChapterMapsBlob)
        assertEquals(2_000L, stored.builtAt)
    }

    @Test
    fun crossEpubIndexClearRemovesEveryRow() = runTest {
        val dao = db.crossEpubIndexDao()
        dao.upsert(CrossEpubIndexEntity("abs-v1", "st-v1", "{}", 1_000L))
        dao.upsert(CrossEpubIndexEntity("abs-v2", "st-v2", "{}", 2_000L))

        dao.clear()

        assertNull(dao.find("abs-v1", "st-v1"))
        assertNull(dao.find("abs-v2", "st-v2"), "clear() must empty the table, not just the first row")
    }

    // ── IosReadaloudResumePositionDao ─────────────────────────────────────────

    @Test
    fun readaloudResumePositionRoundTripsNullProgressionAndFragmentRef() = runTest {
        seedSource(SOURCE_ID)
        val dao = db.readaloudResumePositionDao()
        val entity = ReadaloudResumePositionEntity(
            sourceId = SOURCE_ID,
            itemId = ITEM_ID,
            href = "chapter1.xhtml",
            progression = null,
            fragmentRef = null,
            localUpdatedAt = 500L,
        )

        dao.upsert(entity)

        val stored = dao.getByItemId(SOURCE_ID, ITEM_ID)
        assertNotNull(stored, "Row must be readable after upsert")
        assertEquals(entity, stored)
        assertNull(stored.progression, "A null progression must not be coerced to 0.0")
        assertNull(stored.fragmentRef, "A null fragmentRef must not be coerced to an empty string")
    }

    @Test
    fun readaloudResumePositionUpsertReplacesTheRowForTheSameItem() = runTest {
        seedSource(SOURCE_ID)
        val dao = db.readaloudResumePositionDao()
        dao.upsert(ReadaloudResumePositionEntity(SOURCE_ID, ITEM_ID, "chapter1.xhtml", 0.1, "p1", 100L))

        dao.upsert(ReadaloudResumePositionEntity(SOURCE_ID, ITEM_ID, "chapter4.xhtml", 0.9, "p9", 900L))

        val stored = dao.getByItemId(SOURCE_ID, ITEM_ID)
        assertNotNull(stored)
        assertEquals("chapter4.xhtml", stored.href)
        assertEquals(0.9, stored.progression)
        assertEquals("p9", stored.fragmentRef)
        assertEquals(900L, stored.localUpdatedAt)
    }

    @Test
    fun readaloudResumePositionDeleteByItemIdLeavesOtherItemsIntact() = runTest {
        seedSource(SOURCE_ID)
        val dao = db.readaloudResumePositionDao()
        dao.upsert(ReadaloudResumePositionEntity(SOURCE_ID, ITEM_ID, "chapter1.xhtml", 0.1, null, 100L))
        dao.upsert(ReadaloudResumePositionEntity(SOURCE_ID, OTHER_ITEM_ID, "chapter2.xhtml", 0.2, null, 200L))

        dao.deleteByItemId(SOURCE_ID, ITEM_ID)

        assertNull(dao.getByItemId(SOURCE_ID, ITEM_ID))
        assertNotNull(
            dao.getByItemId(SOURCE_ID, OTHER_ITEM_ID),
            "Clearing one book's readaloud resume point must not clear the whole source",
        )
    }
}
