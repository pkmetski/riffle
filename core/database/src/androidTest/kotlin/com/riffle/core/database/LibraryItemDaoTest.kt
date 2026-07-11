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
import kotlinx.coroutines.yield
import java.util.concurrent.CopyOnWriteArrayList
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LibraryItemDaoTest {

    private lateinit var db: RiffleDatabase
    private lateinit var dao: LibraryItemDao

    @Before
    fun setup() {
        db = Room.inMemoryDatabaseBuilder(
            InstrumentationRegistry.getInstrumentation().targetContext,
            RiffleDatabase::class.java,
        ).allowMainThreadQueries().build()
        dao = db.libraryItemDao()
        // library_items FK-references servers; seed the owning Servers first.
        runBlocking {
            db.sourceDao().upsert(SourceEntity("s1", "http://s1", isActive = true, insecureConnectionAllowed = false, username = "u"))
            db.sourceDao().upsert(SourceEntity("s2", "http://s2", isActive = false, insecureConnectionAllowed = false, username = "u"))
        }
    }

    @After
    fun teardown() {
        db.close()
    }

    private fun item(
        id: String,
        readingProgress: Float,
        lastOpenedAt: Long? = null,
        sourceId: String = "s1",
    ) = LibraryItemEntity(
        sourceId = sourceId,
        id = id,
        libraryId = "lib1",
        title = "Title $id",
        author = "Author",
        coverUrl = null,
        readingProgress = readingProgress,
        lastOpenedAt = lastOpenedAt,
        addedAt = 0L,
    )

    // A1 — observeInProgress returns only items with 0 < readingProgress < 1.0
    @Test
    fun observeInProgress_returnsOnlyInProgressItems() = runTest {
        dao.upsertAll(listOf(
            item("not-started", readingProgress = 0.0f),
            item("in-progress", readingProgress = 0.5f),
            item("finished", readingProgress = 1.0f),
        ))

        val result = dao.observeInProgress("s1", "lib1").first()

        assertEquals(1, result.size)
        assertEquals("in-progress", result[0].id)
    }

    // A2 — observeInProgress sorts by lastOpenedAt descending, nulls last
    @Test
    fun observeInProgress_sortsByLastOpenedAtDescNullsLast() = runTest {
        dao.upsertAll(listOf(
            item("oldest",   readingProgress = 0.3f, lastOpenedAt = 1000L),
            item("newest",   readingProgress = 0.6f, lastOpenedAt = 3000L),
            item("middle",   readingProgress = 0.4f, lastOpenedAt = 2000L),
            item("null-ts",  readingProgress = 0.5f, lastOpenedAt = null),
        ))

        val ids = dao.observeInProgress("s1", "lib1").first().map { it.id }

        assertEquals(listOf("newest", "middle", "oldest", "null-ts"), ids)
    }

    // A3 — observeFinished returns only items at readingProgress == 1.0
    @Test
    fun observeFinished_returnsOnlyFinishedItems() = runTest {
        dao.upsertAll(listOf(
            item("not-started", readingProgress = 0.0f),
            item("in-progress", readingProgress = 0.5f),
            item("finished",    readingProgress = 1.0f),
        ))

        val result = dao.observeFinished("s1", "lib1").first()

        assertEquals(1, result.size)
        assertEquals("finished", result[0].id)
    }

    // A4 — observeAllBooks returns all items regardless of progress
    @Test
    fun observeAllBooks_returnsAllItems() = runTest {
        dao.upsertAll(listOf(
            item("not-started", readingProgress = 0.0f),
            item("in-progress", readingProgress = 0.5f),
            item("finished",    readingProgress = 1.0f),
        ))

        val result = dao.observeAllBooks("s1", "lib1").first()

        assertEquals(3, result.size)
    }

    // A5 — not-started item appears only in observeAllBooks
    @Test
    fun notStartedItem_appearsOnlyInAllBooks() = runTest {
        dao.upsertAll(listOf(item("not-started", readingProgress = 0.0f)))

        val inProgress = dao.observeInProgress("s1", "lib1").first()
        val finished   = dao.observeFinished("s1", "lib1").first()
        val allBooks   = dao.observeAllBooks("s1", "lib1").first()

        assertEquals(0, inProgress.size)
        assertEquals(0, finished.size)
        assertEquals(1, allBooks.size)
    }

    // A0 — two Servers can hold a book with the same itemId without colliding; getById resolves
    // the right one by (sourceId, itemId). This is the core fix for issue #81.
    @Test
    fun getById_distinguishesSameItemIdAcrossServers() = runTest {
        dao.upsertAll(listOf(
            item("1", readingProgress = 0.25f, sourceId = "s1").copy(title = "War and Peace"),
            item("1", readingProgress = 0.5f, sourceId = "s2").copy(title = "A Different Book"),
        ))

        assertEquals("War and Peace", dao.getById("s1", "1")?.title)
        assertEquals("A Different Book", dao.getById("s2", "1")?.title)
    }

    // A0b — when two Servers each host a library with the same id (per issue #113, library ids
    // are only unique within a Server), every item must still be enumerated once. Before the
    // fix this query JOINed by library id alone and multiplied each item by the number of
    // colliding library rows — the duplicates crashed the Readaloud picker's LazyColumn with
    // "Key X was already used."
    @Test
    fun listMatchableByServerType_doesNotDuplicateAcrossServersWithCollidingLibraryIds() = runTest {
        // s1 and s2 are seeded with the default serverType AUDIOBOOKSHELF in @Before.
        db.libraryDao().upsertAll(listOf(
            LibraryEntity(id = "shared-lib", name = "L1", mediaType = "book", sourceId = "s1"),
            LibraryEntity(id = "shared-lib", name = "L1", mediaType = "book", sourceId = "s2"),
        ))
        dao.upsertAll(listOf(
            item("a", readingProgress = 0f, sourceId = "s1").copy(libraryId = "shared-lib"),
            item("b", readingProgress = 0f, sourceId = "s2").copy(libraryId = "shared-lib"),
        ))

        val rows = dao.listMatchableBySourceType("AUDIOBOOKSHELF")
        val keys = rows.map { it.sourceId to it.itemId }

        assertEquals(setOf("s1" to "a", "s2" to "b"), keys.toSet())
        assertEquals("each item must appear once, not duplicated by the library-id JOIN", keys.toSet().size, keys.size)
    }

    // A0c — library-scoped queries (ADR 0025) must isolate by sourceId. Two Servers sharing
    // a library id with overlapping item ids must each see only their own rows.
    @Test
    fun observeByLibraryId_scopesByServerId() = runTest {
        dao.upsertAll(listOf(
            item("1", readingProgress = 0.3f, sourceId = "s1").copy(libraryId = "shared-lib", title = "S1 Book 1"),
            item("2", readingProgress = 0.7f, sourceId = "s1").copy(libraryId = "shared-lib", title = "S1 Book 2"),
            item("1", readingProgress = 0.4f, sourceId = "s2").copy(libraryId = "shared-lib", title = "S2 Book 1"),
        ))

        val s1 = dao.observeByLibraryId("s1", "shared-lib").first().map { it.sourceId to it.id }
        val s2 = dao.observeByLibraryId("s2", "shared-lib").first().map { it.sourceId to it.id }

        assertEquals(setOf("s1" to "1", "s1" to "2"), s1.toSet())
        assertEquals(setOf("s2" to "1"), s2.toSet())
    }

    // A6 — replaceAllForLibrary must never expose an empty intermediate state to observers.
    // If @Transaction is removed the delete emits before the insert, causing a visible flicker.
    @Test
    fun replaceAllForLibrary_neverEmitsEmptyIntermediateState() = runBlocking {
        dao.upsertAll(listOf(item("a", 0.3f), item("b", 0.6f)))

        // Room emits on its own query executor, not this coroutine's scheduler, so we wait for
        // emissions deterministically (with a timeout) rather than racing them with yield().
        val emittedStates = CopyOnWriteArrayList<List<LibraryItemEntity>>()
        val collectJob = launch(Dispatchers.IO) {
            dao.observeAllBooks("s1", "lib1").collect { emittedStates.add(it.toList()) }
        }
        withTimeout(5_000) { while (emittedStates.isEmpty()) delay(10) }

        dao.replaceAllForLibrary("s1", "lib1", listOf(item("c", 0f), item("d", 0.5f), item("e", 1f)))
        withTimeout(5_000) {
            while (emittedStates.last().map { it.id }.toSet() != setOf("c", "d", "e")) delay(10)
        }

        collectJob.cancel()

        assert(emittedStates.none { it.isEmpty() }) {
            "Observed an empty intermediate state — @Transaction may have been removed from replaceAllForLibrary"
        }
        assertEquals(setOf("c", "d", "e"), emittedStates.last().map { it.id }.toSet())
    }

    // observeRecentlyAdded filters out sentinel rows (addedAt == 0), which are written by
    // on-demand browse upserters like ChitankaLibraryItemUpserter. A browse tap is not intent to
    // add the book — the row only exists so the reader / audiobook player can resolve it.
    // Reverting the `AND addedAt > 0` clause on the query flips this test red.
    @Test
    fun observeRecentlyAdded_excludesSentinelRows() = runTest {
        dao.upsertAll(listOf(
            item("browsed", readingProgress = 0f).copy(addedAt = 0L),
            item("added-old", readingProgress = 0f).copy(addedAt = 1_000L),
            item("added-new", readingProgress = 0f).copy(addedAt = 2_000L),
        ))

        val ids = dao.observeRecentlyAdded("s1", "lib1").first().map { it.id }

        assertEquals(listOf("added-new", "added-old"), ids)
    }

    // updateLastOpenedAt is the strong-intent promotion for browse-cached rows: opening the
    // reader flips the sentinel addedAt = 0 to the real timestamp, so the item now surfaces in
    // Recently Added. Rows already carrying a real addedAt must keep it (never overwrite a
    // canonical import date with lastOpenedAt). Removing the CASE branch flips this test red.
    @Test
    fun updateLastOpenedAt_promotesSentinelAddedAtOnFirstOpen() = runTest {
        dao.upsertAll(listOf(
            item("browsed", readingProgress = 0f).copy(addedAt = 0L),
            item("imported", readingProgress = 0f).copy(addedAt = 500L),
        ))

        dao.updateLastOpenedAt("s1", "browsed", timestamp = 9_000L)
        dao.updateLastOpenedAt("s1", "imported", timestamp = 9_000L)

        val rows = dao.observeAllBooks("s1", "lib1").first().associateBy { it.id }
        assertEquals(9_000L, rows.getValue("browsed").addedAt)
        assertEquals(9_000L, rows.getValue("browsed").lastOpenedAt)
        assertEquals(500L, rows.getValue("imported").addedAt)
        assertEquals(9_000L, rows.getValue("imported").lastOpenedAt)
    }
}
