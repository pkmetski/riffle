package com.riffle.core.database.dao

import com.riffle.core.database.LibraryItemEntity
import com.riffle.core.database.RemoteProgressUpdate
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Behavioural coverage for [IosLibraryItemDao], focussed on the batch operations that are
 * most performance-sensitive on iOS: replaceAllForLibrary and batchUpdateReadingProgressFromServer.
 *
 * Both operations were previously executed as O(N) implicit SQLite auto-commit transactions
 * (one fsync per row), causing 20+ second load times for a large ABS library. The iOS
 * overrides in IosLibraryItemDao wrap each batch in a single explicit transaction; these
 * tests pin the correctness of that refactored implementation.
 */
class IosLibraryItemDaoTest : IosDaoTestBase() {

    private val libraryId = "lib-1"
    private val otherLibraryId = "lib-2"

    // ── replaceAllForLibrary ──────────────────────────────────────────────────

    @Test
    fun replaceAllForLibraryInsertsAllItems() = runTest {
        seedSource(SOURCE_ID)
        val items = (1..5).map { i -> makeItem("item-$i") }

        db.libraryItemDao().replaceAllForLibrary(SOURCE_ID, libraryId, items)

        val stored = db.libraryItemDao().listByLibraryId(SOURCE_ID, libraryId)
        assertEquals(5, stored.size)
        val storedIds = stored.map { it.id }.toSet()
        items.forEach { assertTrue(it.id in storedIds) }
    }

    @Test
    fun replaceAllForLibraryDeletesItemsRemovedFromServer() = runTest {
        seedSource(SOURCE_ID)
        val initial = (1..5).map { i -> makeItem("item-$i") }
        db.libraryItemDao().replaceAllForLibrary(SOURCE_ID, libraryId, initial)

        // Server now only returns items 3, 4, 5 — 1 and 2 should be pruned.
        val updated = (3..5).map { i -> makeItem("item-$i") }
        db.libraryItemDao().replaceAllForLibrary(SOURCE_ID, libraryId, updated)

        val stored = db.libraryItemDao().listByLibraryId(SOURCE_ID, libraryId)
        assertEquals(3, stored.size)
        val storedIds = stored.map { it.id }.toSet()
        assertTrue("item-1" !in storedIds)
        assertTrue("item-2" !in storedIds)
        assertTrue("item-3" in storedIds)
    }

    @Test
    fun replaceAllForLibraryDoesNotTouchOtherLibraries() = runTest {
        seedSource(SOURCE_ID)
        val inOtherLibrary = makeItem("item-other", libId = otherLibraryId)
        db.libraryItemDao().replaceAllForLibrary(SOURCE_ID, otherLibraryId, listOf(inOtherLibrary))

        val items = (1..3).map { i -> makeItem("item-$i") }
        db.libraryItemDao().replaceAllForLibrary(SOURCE_ID, libraryId, items)

        val otherLibStored = db.libraryItemDao().listByLibraryId(SOURCE_ID, otherLibraryId)
        assertEquals(1, otherLibStored.size, "Item in other library must survive replaceAllForLibrary on lib-1")
    }

    @Test
    fun replaceAllForLibraryPreservesLocalReadingProgress() = runTest {
        seedSource(SOURCE_ID)
        // First refresh seeds item with progress = 0.
        db.libraryItemDao().replaceAllForLibrary(SOURCE_ID, libraryId, listOf(makeItem(ITEM_ID, progress = 0f)))
        // Simulate local progress saved by the reader.
        db.libraryItemDao().updateReadingProgress(SOURCE_ID, ITEM_ID, 0.5f)

        // Second refresh carries a different server progress value — must NOT overwrite local.
        db.libraryItemDao().replaceAllForLibrary(SOURCE_ID, libraryId, listOf(makeItem(ITEM_ID, progress = 0.8f, title = "Updated Title")))

        val stored = db.libraryItemDao().getById(SOURCE_ID, ITEM_ID)!!
        assertEquals(0.5f, stored.readingProgress, "Local reading progress must not be overwritten by server refresh")
        assertEquals("Updated Title", stored.title, "Metadata (title) must be updated by refresh")
    }

    @Test
    fun replaceAllForLibraryEmptyListDeletesAll() = runTest {
        seedSource(SOURCE_ID)
        db.libraryItemDao().replaceAllForLibrary(SOURCE_ID, libraryId, (1..3).map { makeItem("item-$it") })

        db.libraryItemDao().replaceAllForLibrary(SOURCE_ID, libraryId, emptyList())

        assertTrue(db.libraryItemDao().listByLibraryId(SOURCE_ID, libraryId).isEmpty())
    }

    @Test
    fun replaceAllForLibraryFlowEmitsUpdatedStateAfterBatch() = runTest {
        seedSource(SOURCE_ID)
        val emissions = recordEmissions(db.libraryItemDao().observeByLibraryId(SOURCE_ID, libraryId))

        val items = (1..10).map { i -> makeItem("item-$i") }
        db.libraryItemDao().replaceAllForLibrary(SOURCE_ID, libraryId, items)
        settleEmissions()

        val latest = emissions.last()
        assertEquals(10, latest.size, "Flow must reflect the inserted items after replaceAllForLibrary")
    }

    // ── batchUpdateReadingProgressFromServer ──────────────────────────────────

    @Test
    fun batchUpdateReadingProgressFromServerUpdatesAllItems() = runTest {
        seedSource(SOURCE_ID)
        val items = listOf(makeItem("item-1"), makeItem("item-2"), makeItem("item-3"))
        db.libraryItemDao().replaceAllForLibrary(SOURCE_ID, libraryId, items)

        val updates = listOf(
            RemoteProgressUpdate("item-1", 0.3f, serverUpdatedAt = 1000L),
            RemoteProgressUpdate("item-2", 0.6f, serverUpdatedAt = 1000L),
            RemoteProgressUpdate("item-3", 0.9f, serverUpdatedAt = 1000L),
        )
        db.libraryItemDao().batchUpdateReadingProgressFromServer(SOURCE_ID, updates)

        assertEquals(0.3f, db.libraryItemDao().getById(SOURCE_ID, "item-1")!!.readingProgress)
        assertEquals(0.6f, db.libraryItemDao().getById(SOURCE_ID, "item-2")!!.readingProgress)
        assertEquals(0.9f, db.libraryItemDao().getById(SOURCE_ID, "item-3")!!.readingProgress)
    }

    @Test
    fun batchUpdateReadingProgressFromServerSkipsStaleServerValues() = runTest {
        seedSource(SOURCE_ID)
        db.libraryItemDao().replaceAllForLibrary(SOURCE_ID, libraryId, listOf(makeItem(ITEM_ID)))
        // Write a fresher stamped progress first.
        db.libraryItemDao().updateReadingProgressStamped(SOURCE_ID, ITEM_ID, 0.7f, updatedAt = 2000L)

        // A batch update with an older timestamp must be ignored.
        db.libraryItemDao().batchUpdateReadingProgressFromServer(
            SOURCE_ID,
            listOf(RemoteProgressUpdate(ITEM_ID, 0.2f, serverUpdatedAt = 1000L)),
        )

        assertEquals(0.7f, db.libraryItemDao().getById(SOURCE_ID, ITEM_ID)!!.readingProgress,
            "Stale server progress must not overwrite a fresher locally-stamped value")
    }

    @Test
    fun batchUpdateReadingProgressFromServerEmptyListIsNoOp() = runTest {
        seedSource(SOURCE_ID)
        db.libraryItemDao().replaceAllForLibrary(SOURCE_ID, libraryId, listOf(makeItem(ITEM_ID, progress = 0.5f)))
        db.libraryItemDao().updateReadingProgressStamped(SOURCE_ID, ITEM_ID, 0.5f, updatedAt = 1000L)

        db.libraryItemDao().batchUpdateReadingProgressFromServer(SOURCE_ID, emptyList())

        assertEquals(0.5f, db.libraryItemDao().getById(SOURCE_ID, ITEM_ID)!!.readingProgress)
    }

    @Test
    fun batchUpdateReadingProgressFromServerFlowEmitsAfterBatch() = runTest {
        seedSource(SOURCE_ID)
        val items = listOf(makeItem("item-1"), makeItem("item-2"))
        db.libraryItemDao().replaceAllForLibrary(SOURCE_ID, libraryId, items)
        settleEmissions()

        val emissions = recordEmissions(db.libraryItemDao().observeByLibraryId(SOURCE_ID, libraryId))
        db.libraryItemDao().batchUpdateReadingProgressFromServer(
            SOURCE_ID,
            listOf(RemoteProgressUpdate("item-1", 0.5f, 1000L)),
        )
        settleEmissions()

        val latest = emissions.last()
        assertEquals(0.5f, latest.first { it.id == "item-1" }.readingProgress,
            "Flow must reflect batch progress update")
    }

    @Test
    fun updateReadingProgressFromServerRespectsStamp() = runTest {
        seedSource(SOURCE_ID)
        db.libraryItemDao().replaceAllForLibrary(SOURCE_ID, libraryId, listOf(makeItem(ITEM_ID, progress = 0.2f)))

        db.libraryItemDao().updateReadingProgressFromServer(SOURCE_ID, ITEM_ID, 0.6f, 200L)
        assertEquals(0.6f, db.libraryItemDao().getById(SOURCE_ID, ITEM_ID)!!.readingProgress)
        // Lagging server update (older stamp) must be rejected.
        db.libraryItemDao().updateReadingProgressFromServer(SOURCE_ID, ITEM_ID, 0.1f, 100L)
        assertEquals(0.6f, db.libraryItemDao().getById(SOURCE_ID, ITEM_ID)!!.readingProgress)
        // Newer stamp wins.
        db.libraryItemDao().updateReadingProgressFromServer(SOURCE_ID, ITEM_ID, 0.9f, 300L)
        assertEquals(0.9f, db.libraryItemDao().getById(SOURCE_ID, ITEM_ID)!!.readingProgress)
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private fun makeItem(
        id: String,
        libId: String = libraryId,
        progress: Float = 0f,
        title: String = "Book $id",
    ) = LibraryItemEntity(
        sourceId = SOURCE_ID,
        id = id,
        libraryId = libId,
        title = title,
        author = "Author",
        coverUrl = null,
        readingProgress = progress,
        ebookFileIno = null,
        ebookFormat = "epub",
        hasAudio = false,
        audioDurationSec = 0.0,
        description = null,
        seriesName = null,
        seriesSequence = null,
        publishedYear = null,
        genres = "",
        publisher = null,
        language = null,
        lastOpenedAt = null,
        addedAt = 1_000_000L,
        isbn = null,
        asin = null,
        finishedAt = null,
        pageCount = null,
        progressServerUpdatedAt = 0L,
    )
}
