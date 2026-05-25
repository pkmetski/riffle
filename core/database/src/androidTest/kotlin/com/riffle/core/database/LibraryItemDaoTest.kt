package com.riffle.core.database

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
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
    }

    @After
    fun teardown() {
        db.close()
    }

    private fun item(
        id: String,
        readingProgress: Float,
        lastOpenedAt: Long? = null,
    ) = LibraryItemEntity(
        id = id,
        libraryId = "lib1",
        title = "Title $id",
        author = "Author",
        coverUrl = null,
        readingProgress = readingProgress,
        lastOpenedAt = lastOpenedAt,
    )

    // A1 — observeInProgress returns only items with 0 < readingProgress < 1.0
    @Test
    fun observeInProgress_returnsOnlyInProgressItems() = runTest {
        dao.upsertAll(listOf(
            item("not-started", readingProgress = 0.0f),
            item("in-progress", readingProgress = 0.5f),
            item("finished", readingProgress = 1.0f),
        ))

        val result = dao.observeInProgress("lib1").first()

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

        val ids = dao.observeInProgress("lib1").first().map { it.id }

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

        val result = dao.observeFinished("lib1").first()

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

        val result = dao.observeAllBooks("lib1").first()

        assertEquals(3, result.size)
    }

    // A5 — not-started item appears only in observeAllBooks
    @Test
    fun notStartedItem_appearsOnlyInAllBooks() = runTest {
        dao.upsertAll(listOf(item("not-started", readingProgress = 0.0f)))

        val inProgress = dao.observeInProgress("lib1").first()
        val finished   = dao.observeFinished("lib1").first()
        val allBooks   = dao.observeAllBooks("lib1").first()

        assertEquals(0, inProgress.size)
        assertEquals(0, finished.size)
        assertEquals(1, allBooks.size)
    }

    // A6 — replaceAllForLibrary must never expose an empty intermediate state to observers.
    // If @Transaction is removed the delete emits before the insert, causing a visible flicker.
    @Test
    fun replaceAllForLibrary_neverEmitsEmptyIntermediateState() = runTest {
        dao.upsertAll(listOf(item("a", 0.3f), item("b", 0.6f)))

        val emittedStates = mutableListOf<List<LibraryItemEntity>>()
        val collectJob = launch {
            dao.observeAllBooks("lib1").collect { emittedStates.add(it.toList()) }
        }
        yield() // let the initial emission land before we replace

        dao.replaceAllForLibrary("lib1", listOf(item("c", 0f), item("d", 0.5f), item("e", 1f)))
        yield()

        collectJob.cancel()

        assert(emittedStates.none { it.isEmpty() }) {
            "Observed an empty intermediate state — @Transaction may have been removed from replaceAllForLibrary"
        }
        assertEquals(setOf("c", "d", "e"), emittedStates.last().map { it.id }.toSet())
    }
}
