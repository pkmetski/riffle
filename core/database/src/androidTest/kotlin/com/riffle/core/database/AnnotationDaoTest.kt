package com.riffle.core.database

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AnnotationDaoTest {

    private lateinit var db: RiffleDatabase
    private lateinit var dao: AnnotationDao

    @Before
    fun setup() = runBlocking {
        db = Room.inMemoryDatabaseBuilder(
            InstrumentationRegistry.getInstrumentation().targetContext,
            RiffleDatabase::class.java,
        ).allowMainThreadQueries().build()
        dao = db.annotationDao()
        // annotations.sourceId is a FK to servers(id); seed the ABS servers the tests anchor to.
        val servers = db.sourceDao()
        servers.upsert(SourceEntity("abs1", "http://abs1", isActive = true, insecureConnectionAllowed = false, username = "u"))
        servers.upsert(SourceEntity("abs2", "http://abs2", isActive = false, insecureConnectionAllowed = false, username = "u"))
        servers.upsert(SourceEntity("srv1", "http://srv1", isActive = true, insecureConnectionAllowed = false, username = "u"))
        servers.upsert(SourceEntity("srv2", "http://srv2", isActive = false, insecureConnectionAllowed = false, username = "u"))
    }

    @After
    fun teardown() {
        db.close()
    }

    private fun highlight(
        id: String,
        sourceId: String = "abs1",
        itemId: String = "item1",
        cfi: String = "epubcfi(/6/4!/4/2,/1:0,/1:10)",
        createdAt: Long = 1000L,
        updatedAt: Long = createdAt,
        deleted: Boolean = false,
    ) = AnnotationEntity(
        id = id,
        sourceId = sourceId,
        itemId = itemId,
        type = AnnotationEntity.TYPE_HIGHLIGHT,
        cfi = cfi,
        textSnippet = "the selected text",
        chapterHref = "chapter1.xhtml",
        createdAt = createdAt,
        updatedAt = updatedAt,
        originDeviceId = "device-A",
        lastModifiedByDeviceId = "device-A",
        deleted = deleted,
    )

    private fun bookmark(
        id: String,
        sourceId: String = "abs1",
        itemId: String = "item1",
        cfi: String = "epubcfi(/6/4!/4/2,/1:0)",
        createdAt: Long = 1000L,
        updatedAt: Long = createdAt,
        deleted: Boolean = false,
    ) = AnnotationEntity(
        id = id,
        sourceId = sourceId,
        itemId = itemId,
        type = AnnotationEntity.TYPE_BOOKMARK,
        cfi = cfi,
        textSnippet = "the selected text",
        chapterHref = "chapter1.xhtml",
        createdAt = createdAt,
        updatedAt = updatedAt,
        originDeviceId = "device-A",
        lastModifiedByDeviceId = "device-A",
        deleted = deleted,
    )

    // Tracer bullet: an inserted highlight round-trips back through observeForItem.
    @Test
    fun upsertedHighlight_roundTripsThroughObserveForItem() = runTest {
        dao.upsert(highlight("h1"))

        val result = dao.observeForItem("abs1", "item1").first()

        assertEquals(1, result.size)
        val a = result[0]
        assertEquals("h1", a.id)
        assertEquals(AnnotationEntity.TYPE_HIGHLIGHT, a.type)
        assertEquals(AnnotationEntity.COLOR_YELLOW, a.color)
        assertEquals("chapter1.xhtml", a.chapterHref)
        assertEquals("the selected text", a.textSnippet)
    }

    // A CFI *range* string must survive storage byte-for-byte — it is the load-bearing anchor.
    @Test
    fun cfiRange_persistsIntact() = runTest {
        val range = "epubcfi(/6/14!/4/2/2[c01]/1:7,/4/2/2[c01]/1:42)"
        dao.upsert(highlight("h1", cfi = range))

        assertEquals(range, dao.getById("h1")?.cfi)
    }

    // Annotations are scoped to a single ABS Library Item (sourceId + itemId).
    @Test
    fun observeForItem_isScopedToServerAndItem() = runTest {
        dao.upsert(highlight("h1", sourceId = "abs1", itemId = "item1"))
        dao.upsert(highlight("h2", sourceId = "abs1", itemId = "item2"))
        dao.upsert(highlight("h3", sourceId = "abs2", itemId = "item1"))

        val result = dao.observeForItem("abs1", "item1").first()

        assertEquals(listOf("h1"), result.map { it.id })
    }

    // Tombstoned rows must drop out of the live queries but remain in the table for sync.
    @Test
    fun tombstonedHighlight_isExcludedFromLiveQueriesButRetained() = runTest {
        dao.upsert(highlight("h1"))

        dao.tombstone("h1", updatedAt = 2000L, deviceId = "device-B")

        assertTrue(dao.observeForItem("abs1", "item1").first().isEmpty())
        assertTrue(dao.getForItem("abs1", "item1").isEmpty())
        val tomb = dao.getById("h1")
        assertEquals(true, tomb?.deleted)
        assertEquals(2000L, tomb?.updatedAt)
        assertEquals("device-B", tomb?.lastModifiedByDeviceId)
    }

    // Regression: two annotations tied on (spineIndex, progression) — e.g. a bookmark at chapter
    // top and a highlight on the chapter's first word — must keep a deterministic order across
    // repeated upserts. Before the fix, upsert used INSERT OR REPLACE (which reallocates rowid)
    // and observeAnnotationsByPosition had no tie-breaker, so every sync-driven merge flipped the
    // pair in the annotations panel.
    @Test
    fun observeAnnotationsByPosition_isStableAcrossRepeatedUpsertsOnTiedSortKey() = runTest {
        // Bookmark created first (older createdAt), highlight second. Ids are chosen so lex
        // order (highlight first) is the OPPOSITE of creation order — mirroring
        // AnnotationMergeService's `sortedBy { id }` iteration in the sync merge path. Under the
        // old INSERT-OR-REPLACE upsert this made the re-upsert reallocate rowids in reverse of
        // the intended order and, with no createdAt tie-breaker in the sort, flipped the pair.
        val bookmark = highlight("z-bookmark-chapter-top", createdAt = 1000L).copy(
            type = AnnotationEntity.TYPE_BOOKMARK,
            spineIndex = 141,
            progression = 0.0,
        )
        val hl = highlight("a-highlight-first-word", createdAt = 2000L).copy(
            spineIndex = 141,
            progression = 0.0,
        )
        dao.upsert(bookmark)
        dao.upsert(hl)

        val initial = dao.observeAnnotationsByPosition("abs1", "item1").first().map { it.id }
        assertEquals(listOf("z-bookmark-chapter-top", "a-highlight-first-word"), initial)

        // Simulate the sync merge path — iterate the id-sorted winners and upsert each.
        val idSortedWinners = listOf(hl, bookmark)
        repeat(3) { i ->
            for (row in idSortedWinners) {
                dao.upsert(row.copy(updatedAt = row.updatedAt + i + 1))
            }
        }

        val afterMerges = dao.observeAnnotationsByPosition("abs1", "item1").first().map { it.id }
        assertEquals(listOf("z-bookmark-chapter-top", "a-highlight-first-word"), afterMerges)
    }

    @Test
    fun observeForItem_ordersByCreatedAtAscending() = runTest {
        dao.upsert(highlight("late", createdAt = 3000L))
        dao.upsert(highlight("early", createdAt = 1000L))
        dao.upsert(highlight("mid", createdAt = 2000L))

        val ids = dao.observeForItem("abs1", "item1").first().map { it.id }

        assertEquals(listOf("early", "mid", "late"), ids)
    }

    @Test
    fun recolor_updatesColourAndBumpsUpdatedAtAndDevice() = runTest {
        dao.upsert(highlight("h1", createdAt = 1000L))

        dao.recolor("h1", color = "green", updatedAt = 2000L, deviceId = "device-B")

        val row = dao.getById("h1")
        assertEquals("green", row?.color)
        assertEquals(2000L, row?.updatedAt)
        assertEquals("device-B", row?.lastModifiedByDeviceId)
        // The live query still returns it (recolour is not a delete).
        assertEquals(listOf("h1"), dao.observeForItem("abs1", "item1").first().map { it.id })
    }

    @Test
    fun observeForServer_returnsAllNonDeletedAcrossItems_excludesOtherServers() = runBlocking {
        dao.upsert(highlight("a1", sourceId = "srv1", itemId = "b1"))
        dao.upsert(highlight("a2", sourceId = "srv1", itemId = "b2"))
        dao.upsert(highlight("a3", sourceId = "srv1", itemId = "b3", deleted = true))
        dao.upsert(highlight("a4", sourceId = "srv2", itemId = "b9"))

        val result = dao.observeForSource("srv1").first()

        assertEquals(listOf("a1", "a2"), result.map { it.id })
    }

    // ===== ADR 0038 — purgeAgedTombstones =====

    @Test
    fun purgeAgedTombstones_removesOnlyAgedAlreadySyncedTombstones() = runTest {
        // Live rows must survive regardless of age (only tombstones age).
        dao.upsert(highlight("live-old", createdAt = 0L))
        dao.upsert(highlight("live-fresh", createdAt = 5000L))
        // Aged tomb that has been synced → purge.
        dao.upsert(highlight("tomb-aged-synced", createdAt = 100L, deleted = true).copy(lastSyncedAt = 200L))
        // Aged tomb that never synced → keep (peers haven't received the delete yet).
        dao.upsert(highlight("tomb-aged-unsynced", createdAt = 100L, deleted = true))
        // Fresh tomb (past cutoff by wall-clock) → keep.
        dao.upsert(highlight("tomb-fresh", createdAt = 9_000L, deleted = true).copy(lastSyncedAt = 9_000L))

        val purged = dao.purgeAgedTombstones(sourceId = "abs1", itemId = "item1", cutoff = 5_000L)

        assertEquals(1, purged)
        val remaining = dao.observeForItem("abs1", "item1").first().map { it.id }.toSet()
        val allIncludingDeleted = dao.getAllForItemIncludingDeleted("abs1", "item1").map { it.id }.toSet()
        assertTrue("live-old must survive age-based purge", "live-old" in remaining)
        assertTrue("live-fresh must survive", "live-fresh" in remaining)
        assertTrue("unsynced tomb must survive so peers still receive the delete",
            "tomb-aged-unsynced" in allIncludingDeleted)
        assertTrue("fresh tomb must survive", "tomb-fresh" in allIncludingDeleted)
        assertEquals("aged+synced tomb must be gone", false, "tomb-aged-synced" in allIncludingDeleted)
    }

    @Test
    fun purgeAgedTombstones_isScopedToServerAndItem() = runTest {
        // Same "aged + synced tombstone" pattern under a different itemId and a different sourceId.
        // A per-item purge call must not touch either.
        dao.upsert(highlight("tomb-target", sourceId = "abs1", itemId = "item1", createdAt = 100L, deleted = true)
            .copy(lastSyncedAt = 200L))
        dao.upsert(highlight("tomb-other-item", sourceId = "abs1", itemId = "item2", createdAt = 100L, deleted = true)
            .copy(lastSyncedAt = 200L))
        dao.upsert(highlight("tomb-other-server", sourceId = "abs2", itemId = "item1", createdAt = 100L, deleted = true)
            .copy(lastSyncedAt = 200L))

        val purged = dao.purgeAgedTombstones(sourceId = "abs1", itemId = "item1", cutoff = 5_000L)

        assertEquals(1, purged)
        assertTrue("other-item tomb must survive a scoped purge",
            "tomb-other-item" in dao.getAllForItemIncludingDeleted("abs1", "item2").map { it.id })
        assertTrue("other-server tomb must survive",
            "tomb-other-server" in dao.getAllForItemIncludingDeleted("abs2", "item1").map { it.id })
    }

    // Regression: the Settings WebDAV row displays "$N book(s) pending" and must count books, not
    // annotation rows. Five dirty highlights on one book must read as 1, not 5. See
    // observePendingBookCountAcrossAll.
    @Test
    fun observePendingBookCountAcrossAll_countsDistinctBooks() = runTest {
        // Book A: three dirty rows (updatedAt > lastSyncedAt via the default lastSyncedAt = 0L).
        dao.upsert(highlight("a1", sourceId = "abs1", itemId = "item1", createdAt = 1_000L))
        dao.upsert(highlight("a2", sourceId = "abs1", itemId = "item1", createdAt = 1_100L))
        dao.upsert(highlight("a3", sourceId = "abs1", itemId = "item1", createdAt = 1_200L))
        // Book B: two dirty rows.
        dao.upsert(highlight("b1", sourceId = "abs1", itemId = "item2", createdAt = 1_300L))
        dao.upsert(highlight("b2", sourceId = "abs1", itemId = "item2", createdAt = 1_400L))
        // Book C on a second server: one dirty row.
        dao.upsert(highlight("c1", sourceId = "abs2", itemId = "item1", createdAt = 1_500L))
        // Book D: fully synced (lastSyncedAt == updatedAt) — must NOT count.
        dao.upsert(highlight("d1", sourceId = "abs1", itemId = "item3", createdAt = 1_600L)
            .copy(lastSyncedAt = 1_600L))

        assertEquals(3, dao.observePendingBookCountAcrossAll().first())
    }

    // Once every row for a book is stamped synced, the book must drop out of the count.
    @Test
    fun observePendingBookCountAcrossAll_dropsBookOnceMarkSyncedClearsAllRows() = runTest {
        dao.upsert(highlight("a1", sourceId = "abs1", itemId = "item1", createdAt = 1_000L))
        dao.upsert(highlight("a2", sourceId = "abs1", itemId = "item1", createdAt = 1_100L))
        dao.upsert(highlight("b1", sourceId = "abs1", itemId = "item2", createdAt = 1_200L))

        assertEquals(2, dao.observePendingBookCountAcrossAll().first())

        // Only one of book A's two rows is marked synced — book A still has a dirty row, so still 2.
        dao.markSynced(listOf("a1"), syncedAt = 2_000L)
        assertEquals(2, dao.observePendingBookCountAcrossAll().first())

        // Now the last of book A's rows is clean too — book A drops out.
        dao.markSynced(listOf("a2"), syncedAt = 2_100L)
        assertEquals(1, dao.observePendingBookCountAcrossAll().first())
    }

    // ===== Annotations View — observeBooksWithHighlights =====

    // Book A: 2 highlights (older), Book B: 1 highlight (newer), Book C: only bookmark (excluded),
    // Book D: highlight but soft-deleted (excluded).
    @Test
    fun observeBooksWithHighlights_groupsByItemAndSortsByLatestUpdatedAt() = runTest {
        dao.upsert(highlight("a1", sourceId = "abs1", itemId = "A", createdAt = 100))
        dao.upsert(highlight("a2", sourceId = "abs1", itemId = "A", createdAt = 200))
        dao.upsert(highlight("b1", sourceId = "abs1", itemId = "B", createdAt = 300))
        dao.upsert(bookmark("c1", sourceId = "abs1", itemId = "C", createdAt = 400))
        dao.upsert(highlight("d1", sourceId = "abs1", itemId = "D", createdAt = 500, deleted = true))

        val result = dao.observeBooksWithHighlights("abs1").first()

        assertEquals(2, result.size)
        assertEquals("B", result[0].itemId)
        assertEquals(1, result[0].highlightCount)
        assertEquals(300L, result[0].latestUpdatedAt)
        assertEquals("A", result[1].itemId)
        assertEquals(2, result[1].highlightCount)
        assertEquals(200L, result[1].latestUpdatedAt)
    }

    // A second server's highlights must not leak into the first server's list.
    @Test
    fun observeBooksWithHighlights_isScopedToServer() = runTest {
        dao.upsert(highlight("a1", sourceId = "abs1", itemId = "A", createdAt = 100))
        dao.upsert(highlight("x1", sourceId = "abs2", itemId = "X", createdAt = 100))

        val result = dao.observeBooksWithHighlights("abs1").first()

        assertEquals(listOf("A"), result.map { it.itemId })
    }
}
