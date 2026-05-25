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
class CollectionDaoTest {

    private lateinit var db: RiffleDatabase
    private lateinit var dao: CollectionDao

    @Before
    fun setup() {
        db = Room.inMemoryDatabaseBuilder(
            InstrumentationRegistry.getInstrumentation().targetContext,
            RiffleDatabase::class.java,
        ).allowMainThreadQueries().build()
        dao = db.collectionDao()
    }

    @After
    fun teardown() {
        db.close()
    }

    private fun collection(id: String) = CollectionEntity(id = id, libraryId = "lib1", name = "Collection $id", bookCount = 1)

    // C1 — replaceAllForLibrary must never expose an empty intermediate state to collection observers.
    @Test
    fun replaceAllForLibrary_neverEmitsEmptyIntermediateState() = runTest {
        dao.upsertAll(listOf(collection("c1"), collection("c2")))

        val emittedStates = mutableListOf<List<CollectionEntity>>()
        val collectJob = launch {
            dao.observeByLibraryId("lib1").collect { emittedStates.add(it.toList()) }
        }
        yield()

        dao.replaceAllForLibrary("lib1", listOf(collection("c3"), collection("c4")), emptyList())
        yield()

        collectJob.cancel()

        assert(emittedStates.none { it.isEmpty() }) {
            "Observed an empty intermediate state — @Transaction may have been removed from CollectionDao.replaceAllForLibrary"
        }
        assertEquals(setOf("c3", "c4"), emittedStates.last().map { it.id }.toSet())
    }

    // C2 — observeItemsByCollectionId returns items in title order
    @Test
    fun observeItemsByCollectionId_returnsItemsInTitleOrder() = runTest {
        val items = listOf(
            LibraryItemEntity("item-z", "lib1", "Zorro", "Author", null, 0f),
            LibraryItemEntity("item-a", "lib1", "Asimov", "Author", null, 0f),
            LibraryItemEntity("item-m", "lib1", "Middle", "Author", null, 0f),
        )
        db.libraryItemDao().upsertAll(items)
        dao.upsertAll(listOf(collection("c1")))
        dao.upsertAllItems(listOf(
            CollectionItemEntity("c1", "item-z"),
            CollectionItemEntity("c1", "item-a"),
            CollectionItemEntity("c1", "item-m"),
        ))

        val result = dao.observeItemsByCollectionId("c1").first()

        assertEquals(listOf("item-a", "item-m", "item-z"), result.map { it.id })
    }
}
