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
        // annotations.serverId is a FK to servers(id); seed the ABS servers the tests anchor to.
        val servers = db.serverDao()
        servers.upsert(ServerEntity("abs1", "http://abs1", isActive = true, insecureConnectionAllowed = false, username = "u"))
        servers.upsert(ServerEntity("abs2", "http://abs2", isActive = false, insecureConnectionAllowed = false, username = "u"))
    }

    @After
    fun teardown() {
        db.close()
    }

    private fun highlight(
        id: String,
        serverId: String = "abs1",
        itemId: String = "item1",
        cfi: String = "epubcfi(/6/4!/4/2,/1:0,/1:10)",
        createdAt: Long = 1000L,
        deleted: Boolean = false,
    ) = AnnotationEntity(
        id = id,
        serverId = serverId,
        itemId = itemId,
        cfi = cfi,
        textSnippet = "the selected text",
        chapterHref = "chapter1.xhtml",
        createdAt = createdAt,
        updatedAt = createdAt,
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

    // Annotations are scoped to a single ABS Library Item (serverId + itemId).
    @Test
    fun observeForItem_isScopedToServerAndItem() = runTest {
        dao.upsert(highlight("h1", serverId = "abs1", itemId = "item1"))
        dao.upsert(highlight("h2", serverId = "abs1", itemId = "item2"))
        dao.upsert(highlight("h3", serverId = "abs2", itemId = "item1"))

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
}
