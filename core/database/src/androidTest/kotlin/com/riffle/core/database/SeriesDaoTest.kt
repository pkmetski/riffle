package com.riffle.core.database

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import java.util.concurrent.CopyOnWriteArrayList
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SeriesDaoTest {

    private lateinit var db: RiffleDatabase
    private lateinit var dao: SeriesDao

    @Before
    fun setup() {
        db = Room.inMemoryDatabaseBuilder(
            InstrumentationRegistry.getInstrumentation().targetContext,
            RiffleDatabase::class.java,
        ).allowMainThreadQueries().build()
        dao = db.seriesDao()
        // library_items FK-references servers; seed the owning Server first.
        runBlocking {
            db.serverDao().upsert(ServerEntity("s1", "http://s1", isActive = true, insecureConnectionAllowed = false, username = "u"))
        }
    }

    @After
    fun teardown() {
        db.close()
    }

    private fun series(id: String) = SeriesEntity(id = id, libraryId = "lib1", name = "Series $id", coverUrl = null, bookCount = 1)
    private fun seriesItem(seriesId: String, itemId: String) = SeriesItemEntity(seriesId = seriesId, serverId = "s1", itemId = itemId, sequenceOrder = 1f)

    // B1 — replaceAllForLibrary must never expose an empty intermediate state to series observers.
    @Test
    fun replaceAllForLibrary_neverEmitsEmptyIntermediateState() = runBlocking {
        dao.upsertAll(listOf(series("s1"), series("s2")))

        // Room emits on its own query executor, not this coroutine's scheduler, so we wait for
        // emissions deterministically (with a timeout) rather than racing them with yield().
        val emittedStates = CopyOnWriteArrayList<List<SeriesEntity>>()
        val collectJob = launch(Dispatchers.IO) {
            dao.observeByLibraryId("lib1").collect { emittedStates.add(it.toList()) }
        }
        withTimeout(5_000) { while (emittedStates.isEmpty()) delay(10) }

        dao.replaceAllForLibrary("lib1", listOf(series("s3"), series("s4")), emptyList())
        withTimeout(5_000) {
            while (emittedStates.last().map { it.id }.toSet() != setOf("s3", "s4")) delay(10)
        }

        collectJob.cancel()

        assert(emittedStates.none { it.isEmpty() }) {
            "Observed an empty intermediate state — @Transaction may have been removed from SeriesDao.replaceAllForLibrary"
        }
        assertEquals(setOf("s3", "s4"), emittedStates.last().map { it.id }.toSet())
    }

    // B2 — observeItemsBySeriesId returns items in sequence order
    @Test
    fun observeItemsBySeriesId_returnsItemsInSequenceOrder() = runTest {
        val items = listOf(
            LibraryItemEntity(serverId = "s1", id = "item-1", libraryId = "lib1", title = "Title 1", author = "Author", coverUrl = null, readingProgress = 0f),
            LibraryItemEntity(serverId = "s1", id = "item-2", libraryId = "lib1", title = "Title 2", author = "Author", coverUrl = null, readingProgress = 0f),
            LibraryItemEntity(serverId = "s1", id = "item-3", libraryId = "lib1", title = "Title 3", author = "Author", coverUrl = null, readingProgress = 0f),
        )
        db.libraryItemDao().upsertAll(items)
        dao.upsertAll(listOf(series("s1")))
        dao.upsertAllItems(listOf(
            SeriesItemEntity("s1", serverId = "s1", itemId = "item-3", sequenceOrder = 1f),
            SeriesItemEntity("s1", serverId = "s1", itemId = "item-1", sequenceOrder = 2f),
            SeriesItemEntity("s1", serverId = "s1", itemId = "item-2", sequenceOrder = 3f),
        ))

        val result = dao.observeItemsBySeriesId("s1").first()

        assertEquals(listOf("item-3", "item-1", "item-2"), result.map { it.id })
    }

    // C1 — returns the next unread book per series (min sequenceOrder, readingProgress < 1.0)
    //      only for series that have ≥1 finished book; ordered by most-recently-finished sibling DESC
    @Test
    fun observeContinueSeriesItems_returnsNextUnreadBookPerQualifyingSeries() = runTest {
        // Three items: item-1 finished, item-2 unread, item-3 unread (different series)
        db.libraryItemDao().upsertAll(listOf(
            LibraryItemEntity(serverId = "s1", id = "item-1", libraryId = "lib1", title = "Book 1", author = "A", coverUrl = null, readingProgress = 1.0f, lastOpenedAt = 1000L),
            LibraryItemEntity(serverId = "s1", id = "item-2", libraryId = "lib1", title = "Book 2", author = "A", coverUrl = null, readingProgress = 0f),
            LibraryItemEntity(serverId = "s1", id = "item-3", libraryId = "lib1", title = "Book 3", author = "A", coverUrl = null, readingProgress = 0f),
        ))
        dao.upsertAll(listOf(series("series-A"), series("series-B")))
        dao.upsertAllItems(listOf(
            // series-A: item-1 finished (seq 1), item-2 unread (seq 2) → should return item-2
            SeriesItemEntity("series-A", serverId = "s1", itemId = "item-1", sequenceOrder = 1f),
            SeriesItemEntity("series-A", serverId = "s1", itemId = "item-2", sequenceOrder = 2f),
            // series-B: item-3 only unread, no finished book → must NOT appear
            SeriesItemEntity("series-B", serverId = "s1", itemId = "item-3", sequenceOrder = 1f),
        ))

        val result = dao.observeContinueSeriesItems("lib1").first()

        assertEquals(listOf("item-2"), result.map { it.id })
    }

    // C2 — partially-read book (0.5 < 1.0) qualifies as the next book; returned at min sequenceOrder
    @Test
    fun observeContinueSeriesItems_treatsPartiallyReadBookAsNext() = runTest {
        db.libraryItemDao().upsertAll(listOf(
            LibraryItemEntity(serverId = "s1", id = "item-1", libraryId = "lib1", title = "B1", author = "A", coverUrl = null, readingProgress = 1.0f, lastOpenedAt = 2000L),
            LibraryItemEntity(serverId = "s1", id = "item-2", libraryId = "lib1", title = "B2", author = "A", coverUrl = null, readingProgress = 0.5f),
            LibraryItemEntity(serverId = "s1", id = "item-3", libraryId = "lib1", title = "B3", author = "A", coverUrl = null, readingProgress = 0f),
        ))
        dao.upsertAll(listOf(series("series-A")))
        dao.upsertAllItems(listOf(
            SeriesItemEntity("series-A", serverId = "s1", itemId = "item-1", sequenceOrder = 1f),
            SeriesItemEntity("series-A", serverId = "s1", itemId = "item-2", sequenceOrder = 2f),
            SeriesItemEntity("series-A", serverId = "s1", itemId = "item-3", sequenceOrder = 3f),
        ))

        // item-2 has readingProgress = 0.5 which is < 1.0, so it IS the next book
        val result = dao.observeContinueSeriesItems("lib1").first()

        assertEquals(listOf("item-2"), result.map { it.id })
    }

    // C3 — multiple qualifying series ordered by most-recently-finished sibling DESC
    @Test
    fun observeContinueSeriesItems_orderedByMostRecentlyFinished() = runTest {
        db.libraryItemDao().upsertAll(listOf(
            // series-old: finished long ago
            LibraryItemEntity(serverId = "s1", id = "old-done", libraryId = "lib1", title = "OD", author = "A", coverUrl = null, readingProgress = 1.0f, lastOpenedAt = 1000L),
            LibraryItemEntity(serverId = "s1", id = "old-next", libraryId = "lib1", title = "ON", author = "A", coverUrl = null, readingProgress = 0f),
            // series-new: finished recently
            LibraryItemEntity(serverId = "s1", id = "new-done", libraryId = "lib1", title = "ND", author = "A", coverUrl = null, readingProgress = 1.0f, lastOpenedAt = 9000L),
            LibraryItemEntity(serverId = "s1", id = "new-next", libraryId = "lib1", title = "NN", author = "A", coverUrl = null, readingProgress = 0f),
        ))
        dao.upsertAll(listOf(series("series-old"), series("series-new")))
        dao.upsertAllItems(listOf(
            SeriesItemEntity("series-old", serverId = "s1", itemId = "old-done", sequenceOrder = 1f),
            SeriesItemEntity("series-old", serverId = "s1", itemId = "old-next", sequenceOrder = 2f),
            SeriesItemEntity("series-new", serverId = "s1", itemId = "new-done", sequenceOrder = 1f),
            SeriesItemEntity("series-new", serverId = "s1", itemId = "new-next", sequenceOrder = 2f),
        ))

        val result = dao.observeContinueSeriesItems("lib1").first()

        // series-new finished more recently → new-next must come first
        assertEquals(listOf("new-next", "old-next"), result.map { it.id })
    }

    // C4 — series with null lastOpenedAt on finished sibling still appears (sorted last)
    @Test
    fun observeContinueSeriesItems_includesSeriesWithNullLastOpenedAt() = runTest {
        db.libraryItemDao().upsertAll(listOf(
            // series-timestamped: finished with a real lastOpenedAt
            LibraryItemEntity(serverId = "s1", id = "ts-done", libraryId = "lib1", title = "TD", author = "A", coverUrl = null, readingProgress = 1.0f, lastOpenedAt = 5000L),
            LibraryItemEntity(serverId = "s1", id = "ts-next", libraryId = "lib1", title = "TN", author = "A", coverUrl = null, readingProgress = 0f),
            // series-null: finished with null lastOpenedAt (ABS sometimes omits this field)
            LibraryItemEntity(serverId = "s1", id = "null-done", libraryId = "lib1", title = "ND", author = "A", coverUrl = null, readingProgress = 1.0f, lastOpenedAt = null),
            LibraryItemEntity(serverId = "s1", id = "null-next", libraryId = "lib1", title = "NN", author = "A", coverUrl = null, readingProgress = 0f),
        ))
        dao.upsertAll(listOf(series("series-ts"), series("series-null")))
        dao.upsertAllItems(listOf(
            SeriesItemEntity("series-ts", serverId = "s1", itemId = "ts-done", sequenceOrder = 1f),
            SeriesItemEntity("series-ts", serverId = "s1", itemId = "ts-next", sequenceOrder = 2f),
            SeriesItemEntity("series-null", serverId = "s1", itemId = "null-done", sequenceOrder = 1f),
            SeriesItemEntity("series-null", serverId = "s1", itemId = "null-next", sequenceOrder = 2f),
        ))

        val result = dao.observeContinueSeriesItems("lib1").first()

        // Both series appear; series-ts (real timestamp) before series-null (null → COALESCE 0)
        assertEquals(listOf("ts-next", "null-next"), result.map { it.id })
    }
}
