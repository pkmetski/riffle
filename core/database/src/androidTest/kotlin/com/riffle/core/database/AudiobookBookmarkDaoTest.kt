package com.riffle.core.database

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AudiobookBookmarkDaoTest {

    private lateinit var db: RiffleDatabase
    private lateinit var dao: AudiobookBookmarkDao

    @Before
    fun setup() = runBlocking {
        db = Room.inMemoryDatabaseBuilder(
            InstrumentationRegistry.getInstrumentation().targetContext,
            RiffleDatabase::class.java,
        ).allowMainThreadQueries().build()
        dao = db.audiobookBookmarkDao()
        // audiobook_bookmarks.sourceId is a FK to servers(id) (ON DELETE CASCADE); seed it first.
        db.sourceDao().upsert(
            SourceEntity("s1", "http://s1", isActive = true, insecureConnectionAllowed = false, username = "u"),
        )
    }

    @After
    fun teardown() {
        db.close()
    }

    @Test
    fun observeReturnsNonDeletedOrderedByPosition() = runTest {
        dao.upsert(AudiobookBookmarkEntity("b2", "s1", "i1", 200.0, "two", 2, 2, 0, false))
        dao.upsert(AudiobookBookmarkEntity("b1", "s1", "i1", 100.0, "one", 1, 1, 0, false))
        dao.upsert(AudiobookBookmarkEntity("bd", "s1", "i1", 50.0, "gone", 3, 3, 0, true))

        val rows = dao.observeForItem("s1", "i1").first()

        assertEquals(listOf("b1", "b2"), rows.map { it.id })
    }

    @Test
    fun dirtyForServerReturnsDirtyIncludingTombstones() = runTest {
        dao.upsert(AudiobookBookmarkEntity("clean", "s1", "i1", 10.0, "c", 1, 5, 5, false))
        dao.upsert(AudiobookBookmarkEntity("dirty", "s1", "i1", 20.0, "d", 1, 6, 5, false))
        dao.upsert(AudiobookBookmarkEntity("tomb", "s1", "i1", 30.0, "t", 1, 7, 5, true))

        val dirty = dao.dirtyForSource("s1").map { it.id }.toSet()

        assertEquals(setOf("dirty", "tomb"), dirty)
    }

    @Test
    fun confirmPushedIfUnchangedClearsDirtyOnlyWhenStampMatches() = runTest {
        dao.upsert(AudiobookBookmarkEntity("b", "s1", "i1", 10.0, "x", 1, 6, 5, false))

        // stale stamp: no-op
        assertEquals(0, dao.confirmPushedIfUnchanged("b", serverStamp = 9, ifLocalUpdatedAt = 999))
        // matching stamp: clears dirty
        assertEquals(1, dao.confirmPushedIfUnchanged("b", serverStamp = 9, ifLocalUpdatedAt = 6))

        val row = dao.getById("b")!!
        assertEquals(9L, row.localUpdatedAt)
        assertEquals(9L, row.lastSyncedAt)
    }
}
