package com.riffle.core.database.dao

import com.riffle.core.database.AudioPlaybackPreferencesEntity
import com.riffle.core.database.AudiobookBookmarkEntity
import com.riffle.core.database.AudiobookChapterCacheEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Behavioural coverage for the audio-side DAOs on iOS (`IosAudioPlaybackPreferencesDao`,
 * `IosAudiobookBookmarkDao`, `IosAudiobookChapterCacheDao`).
 *
 * The bookmark scenarios mirror Android's `AudiobookBookmarkDaoTest` assertion for assertion, so
 * the ADR 0036 dirty-tracking and soft-delete contract is pinned on the iOS code path too — that
 * DAO has its own hand-written SQL, so a green Android test proves nothing about it.
 */
class IosAudioDaoTest : IosDaoTestBase() {

    private fun bookmark(
        id: String,
        positionSec: Double,
        title: String,
        itemId: String = ITEM_ID,
        createdAt: Long = 1L,
        localUpdatedAt: Long = 1L,
        lastSyncedAt: Long = 0L,
        deleted: Boolean = false,
        sourceId: String = SOURCE_ID,
    ) = AudiobookBookmarkEntity(
        id = id,
        sourceId = sourceId,
        itemId = itemId,
        positionSec = positionSec,
        title = title,
        createdAt = createdAt,
        localUpdatedAt = localUpdatedAt,
        lastSyncedAt = lastSyncedAt,
        deleted = deleted,
    )

    // ── IosAudioPlaybackPreferencesDao ────────────────────────────────────────

    @Test
    fun audioPlaybackPreferencesRoundTripsNullSpeed() = runTest {
        seedSource(SOURCE_ID)
        val dao = db.audioPlaybackPreferencesDao()
        // `speed` is nullable so the table can grow further audio-setting columns without forcing
        // this one; a null must survive the Float→Double→Float hop rather than landing as 0.0.
        val entity = AudioPlaybackPreferencesEntity(sourceId = SOURCE_ID, bookId = ITEM_ID, speed = null)

        dao.upsert(entity)

        val stored = dao.get(SOURCE_ID, ITEM_ID)
        assertNotNull(stored, "Row must be readable after upsert")
        assertNull(stored.speed, "A null speed must not be coerced to 0.0")
    }

    @Test
    fun audioPlaybackPreferencesUpsertReplacesSpeedForTheSameBook() = runTest {
        seedSource(SOURCE_ID)
        val dao = db.audioPlaybackPreferencesDao()
        dao.upsert(AudioPlaybackPreferencesEntity(SOURCE_ID, ITEM_ID, 1.5f))

        dao.upsert(AudioPlaybackPreferencesEntity(SOURCE_ID, ITEM_ID, 2.0f))

        assertEquals(2.0f, dao.get(SOURCE_ID, ITEM_ID)?.speed)
    }

    @Test
    fun audioPlaybackPreferencesDeleteLeavesOtherBooksIntact() = runTest {
        seedSource(SOURCE_ID)
        val dao = db.audioPlaybackPreferencesDao()
        dao.upsert(AudioPlaybackPreferencesEntity(SOURCE_ID, ITEM_ID, 1.5f))
        dao.upsert(AudioPlaybackPreferencesEntity(SOURCE_ID, OTHER_ITEM_ID, 0.75f))

        dao.delete(SOURCE_ID, ITEM_ID)

        assertNull(dao.get(SOURCE_ID, ITEM_ID), "Targeted row must be gone")
        assertEquals(
            0.75f,
            dao.get(SOURCE_ID, OTHER_ITEM_ID)?.speed,
            "Resetting one book to the 1x default must not reset the rest of the source",
        )
    }

    // ── IosAudiobookBookmarkDao ───────────────────────────────────────────────

    @Test
    fun audiobookBookmarkObserveForItemExcludesTombstonesAndOrdersByPosition() = runTest {
        seedSource(SOURCE_ID)
        val dao = db.audiobookBookmarkDao()
        dao.upsert(bookmark("b2", 200.0, "two", createdAt = 2, localUpdatedAt = 2))
        dao.upsert(bookmark("b1", 100.0, "one", createdAt = 1, localUpdatedAt = 1))
        dao.upsert(bookmark("bd", 50.0, "gone", createdAt = 3, localUpdatedAt = 3, deleted = true))

        val rows = dao.observeForItem(SOURCE_ID, ITEM_ID).first()

        assertEquals(
            listOf("b1", "b2"),
            rows.map { it.id },
            "A tombstone must stay out of the visible list even though it sorts first by position",
        )
    }

    @Test
    fun audiobookBookmarkAllForItemIncludesTombstones() = runTest {
        seedSource(SOURCE_ID)
        val dao = db.audiobookBookmarkDao()
        dao.upsert(bookmark("b1", 100.0, "one"))
        dao.upsert(bookmark("bd", 50.0, "gone", deleted = true))

        assertEquals(
            setOf("b1", "bd"),
            dao.allForItem(SOURCE_ID, ITEM_ID).map { it.id }.toSet(),
            "The reconciler's read must see tombstones; only the UI-facing observe filters them out",
        )
    }

    @Test
    fun audiobookBookmarkObserveForSourceSpansItemsButStillHidesTombstones() = runTest {
        seedSource(SOURCE_ID)
        seedSource(OTHER_SOURCE_ID)
        val dao = db.audiobookBookmarkDao()
        dao.upsert(bookmark("b1", 100.0, "one"))
        dao.upsert(bookmark("b2", 50.0, "two", itemId = OTHER_ITEM_ID))
        dao.upsert(bookmark("bd", 10.0, "gone", itemId = OTHER_ITEM_ID, deleted = true))
        dao.upsert(bookmark("bx", 20.0, "other source", sourceId = OTHER_SOURCE_ID))

        val rows = dao.observeForSource(SOURCE_ID).first()

        assertEquals(listOf("b2", "b1"), rows.map { it.id }, "Rows must span items and sort by position")
    }

    @Test
    fun audiobookBookmarkDirtyForSourceIncludesTombstones() = runTest {
        seedSource(SOURCE_ID)
        val dao = db.audiobookBookmarkDao()
        dao.upsert(bookmark("clean", 10.0, "c", localUpdatedAt = 5, lastSyncedAt = 5))
        dao.upsert(bookmark("dirty", 20.0, "d", localUpdatedAt = 6, lastSyncedAt = 5))
        dao.upsert(bookmark("tomb", 30.0, "t", localUpdatedAt = 7, lastSyncedAt = 5, deleted = true))

        val dirty = dao.dirtyForSource(SOURCE_ID).map { it.id }.toSet()

        assertEquals(
            setOf("dirty", "tomb"),
            dirty,
            "A pending delete is still an unpushed change, so it must be part of the dirty set",
        )
    }

    @Test
    fun audiobookBookmarkSourcesWithDirtyRowsDeduplicatesAndSkipsCleanSources() = runTest {
        seedSource(SOURCE_ID)
        seedSource(OTHER_SOURCE_ID)
        val dao = db.audiobookBookmarkDao()
        dao.upsert(bookmark("d1", 10.0, "a", localUpdatedAt = 6, lastSyncedAt = 5))
        dao.upsert(bookmark("d2", 20.0, "b", localUpdatedAt = 7, lastSyncedAt = 5))
        dao.upsert(bookmark("clean", 30.0, "c", sourceId = OTHER_SOURCE_ID, localUpdatedAt = 5, lastSyncedAt = 5))

        assertEquals(
            listOf(SOURCE_ID),
            dao.sourcesWithDirtyRows(),
            "Two dirty rows on one source must schedule one push, and a clean source none",
        )
    }

    @Test
    fun audiobookBookmarkConfirmPushedIfUnchangedClearsDirtyOnlyWhenStampMatches() = runTest {
        seedSource(SOURCE_ID)
        val dao = db.audiobookBookmarkDao()
        dao.upsert(bookmark("b", 10.0, "x", localUpdatedAt = 6, lastSyncedAt = 5))

        // Stale stamp: the row changed again while the push was in flight, so it must stay dirty.
        assertEquals(0, dao.confirmPushedIfUnchanged("b", serverStamp = 9, ifLocalUpdatedAt = 999))
        assertEquals(setOf("b"), dao.dirtyForSource(SOURCE_ID).map { it.id }.toSet())

        assertEquals(1, dao.confirmPushedIfUnchanged("b", serverStamp = 9, ifLocalUpdatedAt = 6))

        val row = dao.getById("b")
        assertNotNull(row)
        assertEquals(9L, row.localUpdatedAt)
        assertEquals(9L, row.lastSyncedAt)
        assertTrue(dao.dirtyForSource(SOURCE_ID).isEmpty(), "A confirmed push must clear the dirty flag")
    }

    @Test
    fun audiobookBookmarkHardDeleteIfUnchangedOnlyRemovesConfirmedTombstones() = runTest {
        seedSource(SOURCE_ID)
        val dao = db.audiobookBookmarkDao()
        dao.upsert(bookmark("live", 10.0, "still here", localUpdatedAt = 6))
        dao.upsert(bookmark("tomb", 20.0, "pending delete", localUpdatedAt = 6, deleted = true))

        // A live row is never hard-deleted by the sync path, whatever stamp is offered.
        assertEquals(0, dao.hardDeleteIfUnchanged("live", ifLocalUpdatedAt = 6))
        assertNotNull(dao.getById("live"), "A non-tombstone row must survive hardDeleteIfUnchanged")

        // A tombstone that changed since the delete was issued is not confirmed either.
        assertEquals(0, dao.hardDeleteIfUnchanged("tomb", ifLocalUpdatedAt = 999))
        assertNotNull(dao.getById("tomb"), "A tombstone with a stale stamp must survive")

        assertEquals(1, dao.hardDeleteIfUnchanged("tomb", ifLocalUpdatedAt = 6))
        assertNull(dao.getById("tomb"), "A confirmed tombstone must be hard-removed")
    }

    @Test
    fun audiobookBookmarkHardDeleteRemovesOnlyTheTargetedRow() = runTest {
        seedSource(SOURCE_ID)
        val dao = db.audiobookBookmarkDao()
        dao.upsert(bookmark("b1", 10.0, "one"))
        dao.upsert(bookmark("b2", 20.0, "two"))

        dao.hardDelete("b1")

        assertNull(dao.getById("b1"))
        assertNotNull(dao.getById("b2"))
    }

    @Test
    fun audiobookBookmarkObserveForItemEmitsAgainOnWriteAndOnTombstone() = runTest {
        seedSource(SOURCE_ID)
        val dao = db.audiobookBookmarkDao()
        val emissions = recordEmissions(dao.observeForItem(SOURCE_ID, ITEM_ID))
        assertEquals(1, emissions.size, "A new subscriber must get the current contents immediately")
        assertEquals(emptyList<AudiobookBookmarkEntity>(), emissions.last())

        dao.upsert(bookmark("b1", 10.0, "one"))
        settleEmissions()
        assertEquals(2, emissions.size, "An upsert must push a new value to an existing subscriber")
        assertEquals(listOf("b1"), emissions.last().map { it.id })

        // Soft-deleting is an UPDATE-shaped upsert; the list must still drop the row live.
        dao.upsert(bookmark("b1", 10.0, "one", localUpdatedAt = 2, deleted = true))
        settleEmissions()
        assertEquals(3, emissions.size)
        assertEquals(emptyList<AudiobookBookmarkEntity>(), emissions.last())
    }

    @Test
    fun audiobookBookmarkObserveDirtyCountForItemEmitsAgainOnSyncConfirmation() = runTest {
        seedSource(SOURCE_ID)
        val dao = db.audiobookBookmarkDao()
        val emissions = recordEmissions(dao.observeDirtyCountForItem(SOURCE_ID, ITEM_ID))
        assertEquals(listOf(0), emissions)

        dao.upsert(bookmark("b1", 10.0, "one", localUpdatedAt = 6, lastSyncedAt = 5))
        settleEmissions()
        assertEquals(2, emissions.size)
        assertEquals(1, emissions.last(), "An unpushed bookmark must raise the badge count live")

        dao.confirmPushedIfUnchanged("b1", serverStamp = 9, ifLocalUpdatedAt = 6)
        settleEmissions()
        assertEquals(3, emissions.size, "confirmPushedIfUnchanged must notify live subscribers")
        assertEquals(0, emissions.last(), "A confirmed push must clear the badge count live")
    }

    @Test
    fun audiobookBookmarkFailedConfirmationDoesNotNotifySubscribers() = runTest {
        seedSource(SOURCE_ID)
        val dao = db.audiobookBookmarkDao()
        dao.upsert(bookmark("b1", 10.0, "one", localUpdatedAt = 6, lastSyncedAt = 5))
        val emissions = recordEmissions(dao.observeDirtyCountForItem(SOURCE_ID, ITEM_ID))
        assertEquals(listOf(1), emissions)

        assertEquals(0, dao.confirmPushedIfUnchanged("b1", serverStamp = 9, ifLocalUpdatedAt = 999))
        settleEmissions()

        assertEquals(1, emissions.size, "A no-op UPDATE must not invalidate every live query in the app")
    }

    @Test
    fun audiobookBookmarkObserveDirtyCountIsScopedToItsItem() = runTest {
        seedSource(SOURCE_ID)
        val dao = db.audiobookBookmarkDao()
        dao.upsert(bookmark("b1", 10.0, "one", localUpdatedAt = 6, lastSyncedAt = 5))
        dao.upsert(bookmark("b2", 20.0, "two", itemId = OTHER_ITEM_ID, localUpdatedAt = 6, lastSyncedAt = 5))

        assertEquals(1, dao.observeDirtyCountForItem(SOURCE_ID, ITEM_ID).first())
        assertEquals(1, dao.observeDirtyCountForItem(SOURCE_ID, OTHER_ITEM_ID).first())
        assertEquals(0, dao.observeDirtyCountForItem(SOURCE_ID, "item-unknown").first())
    }

    @Test
    fun audiobookBookmarkUpsertReplacesTheRowWithTheSameId() = runTest {
        seedSource(SOURCE_ID)
        val dao = db.audiobookBookmarkDao()
        dao.upsert(bookmark("b1", 10.0, "original"))

        dao.upsert(bookmark("b1", 42.5, "renamed", localUpdatedAt = 2))

        assertEquals(1, dao.allForItem(SOURCE_ID, ITEM_ID).size, "Re-upserting an id must not duplicate it")
        val stored = dao.getById("b1")
        assertNotNull(stored)
        assertEquals("renamed", stored.title)
        assertEquals(42.5, stored.positionSec)
        assertFalse(stored.deleted)
    }

    // ── IosAudiobookChapterCacheDao ───────────────────────────────────────────

    @Test
    fun audiobookChapterCacheGetReturnsNullForAnUncachedItem() = runTest {
        seedSource(SOURCE_ID)
        val dao = db.audiobookChapterCacheDao()
        dao.upsert(AudiobookChapterCacheEntity(SOURCE_ID, ITEM_ID, "[]", 1_000L))

        assertNull(
            dao.get(SOURCE_ID, OTHER_ITEM_ID),
            "A cache miss must be null so the caller refetches, not an empty chapter list",
        )
    }

    @Test
    fun audiobookChapterCacheUpsertReplacesThePayloadAndTtlStamp() = runTest {
        seedSource(SOURCE_ID)
        val dao = db.audiobookChapterCacheDao()
        dao.upsert(AudiobookChapterCacheEntity(SOURCE_ID, ITEM_ID, "[{\"t\":0}]", 1_000L))

        dao.upsert(AudiobookChapterCacheEntity(SOURCE_ID, ITEM_ID, "[{\"t\":1}]", 2_000L))

        val stored = dao.get(SOURCE_ID, ITEM_ID)
        assertNotNull(stored)
        assertEquals("[{\"t\":1}]", stored.chaptersJson)
        assertEquals(2_000L, stored.cachedAt, "A refetch must move the TTL stamp forward")
    }

    @Test
    fun audiobookChapterCacheRowsAreRemovedByTheSourceDeleteGraph() = runTest {
        seedSource(SOURCE_ID)
        seedSource(OTHER_SOURCE_ID)
        val dao = db.audiobookChapterCacheDao()
        dao.upsert(AudiobookChapterCacheEntity(SOURCE_ID, ITEM_ID, "[]", 1_000L))
        dao.upsert(AudiobookChapterCacheEntity(OTHER_SOURCE_ID, ITEM_ID, "[]", 1_000L))

        db.sourceDao().deleteSourceGraph(SOURCE_ID)

        assertNull(dao.get(SOURCE_ID, ITEM_ID), "Removing a source must drop its cached chapter lists")
        assertNotNull(dao.get(OTHER_SOURCE_ID, ITEM_ID), "Another source's cache must survive")
    }
}
