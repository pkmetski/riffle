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
        // library_items FK-references servers; seed the owning Server first.
        runBlocking {
            db.sourceDao().upsert(SourceEntity("s1", "http://s1", isActive = true, insecureConnectionAllowed = false, username = "u"))
        }
    }

    @After
    fun teardown() {
        db.close()
    }

    private fun collection(id: String) = CollectionEntity(id = id, libraryId = "lib1", name = "Collection $id", bookCount = 1)

    // C1 — replaceAllForLibrary must never expose an empty intermediate state to collection observers.
    @Test
    fun replaceAllForLibrary_neverEmitsEmptyIntermediateState() = runBlocking {
        dao.upsertAll(listOf(collection("c1"), collection("c2")))

        // Room emits on its own query executor, not this coroutine's scheduler, so we wait for
        // emissions deterministically (with a timeout) rather than racing them with yield().
        val emittedStates = CopyOnWriteArrayList<List<CollectionEntity>>()
        val collectJob = launch(Dispatchers.IO) {
            dao.observeByLibraryId("lib1").collect { emittedStates.add(it.toList()) }
        }
        withTimeout(5_000) { while (emittedStates.isEmpty()) delay(10) }

        dao.replaceAllForLibrary("lib1", listOf(collection("c3"), collection("c4")), emptyList())
        withTimeout(5_000) {
            while (emittedStates.last().map { it.id }.toSet() != setOf("c3", "c4")) delay(10)
        }

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
            LibraryItemEntity(sourceId = "s1", id = "item-z", libraryId = "lib1", title = "Zorro", author = "Author", coverUrl = null, readingProgress = 0f),
            LibraryItemEntity(sourceId = "s1", id = "item-a", libraryId = "lib1", title = "Asimov", author = "Author", coverUrl = null, readingProgress = 0f),
            LibraryItemEntity(sourceId = "s1", id = "item-m", libraryId = "lib1", title = "Middle", author = "Author", coverUrl = null, readingProgress = 0f),
        )
        db.libraryItemDao().upsertAll(items)
        dao.upsertAll(listOf(collection("c1")))
        dao.upsertAllItems(listOf(
            CollectionItemEntity("c1", sourceId = "s1", itemId = "item-z"),
            CollectionItemEntity("c1", sourceId = "s1", itemId = "item-a"),
            CollectionItemEntity("c1", sourceId = "s1", itemId = "item-m"),
        ))

        val result = dao.observeItemsByCollectionId("s1", "c1").first()

        assertEquals(listOf("item-a", "item-m", "item-z"), result.map { it.id })
    }
}
