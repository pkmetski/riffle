package com.riffle.core.database

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
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
    fun replaceAllForLibrary_neverEmitsEmptyIntermediateState() = runTest {
        dao.upsertAll(listOf(series("s1"), series("s2")))

        val emittedStates = mutableListOf<List<SeriesEntity>>()
        val collectJob = launch {
            dao.observeByLibraryId("lib1").collect { emittedStates.add(it.toList()) }
        }
        yield()

        dao.replaceAllForLibrary("lib1", listOf(series("s3"), series("s4")), emptyList())
        yield()

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
}
