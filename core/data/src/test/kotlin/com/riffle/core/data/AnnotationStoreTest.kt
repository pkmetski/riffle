package com.riffle.core.data

import com.riffle.core.database.AnnotationDao
import com.riffle.core.database.AnnotationEntity
import com.riffle.core.domain.DeviceIdStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AnnotationStoreTest {

    private class FakeAnnotationDao : AnnotationDao {
        val rows = MutableStateFlow<List<AnnotationEntity>>(emptyList())
        override fun observeForItem(serverId: String, itemId: String): Flow<List<AnnotationEntity>> =
            rows.map { list ->
                list.filter { it.serverId == serverId && it.itemId == itemId && !it.deleted }
                    .sortedBy { it.createdAt }
            }
        override suspend fun getForItem(serverId: String, itemId: String): List<AnnotationEntity> =
            rows.value.filter { it.serverId == serverId && it.itemId == itemId && !it.deleted }
                .sortedBy { it.createdAt }
        override suspend fun getAllForItemIncludingDeleted(serverId: String, itemId: String): List<AnnotationEntity> =
            rows.value.filter { it.serverId == serverId && it.itemId == itemId }
                .sortedBy { it.createdAt }
        override suspend fun getById(id: String): AnnotationEntity? = rows.value.firstOrNull { it.id == id }
        override suspend fun getByItemAndCfi(serverId: String, itemId: String, cfi: String): AnnotationEntity? =
            rows.value.firstOrNull { it.serverId == serverId && it.itemId == itemId && it.cfi == cfi && !it.deleted }
        override suspend fun upsert(entity: AnnotationEntity) {
            rows.value = rows.value.filterNot { it.id == entity.id } + entity
        }
        override suspend fun upsertAll(annotations: List<AnnotationEntity>) {
            val idsToRemove = annotations.map { it.id }.toSet()
            rows.value = rows.value.filterNot { it.id in idsToRemove } + annotations
        }
        override suspend fun tombstone(id: String, updatedAt: Long, deviceId: String) {
            rows.value = rows.value.map {
                if (it.id == id) it.copy(deleted = true, updatedAt = updatedAt, lastModifiedByDeviceId = deviceId) else it
            }
        }
        override suspend fun recolor(id: String, color: String, updatedAt: Long, deviceId: String) {
            rows.value = rows.value.map {
                if (it.id == id) it.copy(color = color, updatedAt = updatedAt, lastModifiedByDeviceId = deviceId) else it
            }
        }
        override suspend fun updateNote(id: String, note: String?, updatedAt: Long, deviceId: String) {
            rows.value = rows.value.map {
                if (it.id == id) it.copy(note = note, updatedAt = updatedAt, lastModifiedByDeviceId = deviceId) else it
            }
        }

        override fun observeAnnotationsByPosition(serverId: String, itemId: String): Flow<List<AnnotationEntity>> =
            rows.map { list ->
                list.filter { it.serverId == serverId && it.itemId == itemId && !it.deleted }
                    .sortedWith(compareBy({ it.spineIndex }, { it.progression }))
            }

        override suspend fun renameBookmark(id: String, title: String, updatedAt: Long, deviceId: String) {
            rows.value = rows.value.map {
                if (it.id == id) it.copy(bookmarkTitle = title, updatedAt = updatedAt, lastModifiedByDeviceId = deviceId) else it
            }
        }

        override fun observeForServer(serverId: String): Flow<List<AnnotationEntity>> =
            rows.map { all -> all.filter { it.serverId == serverId && !it.deleted } }

        override fun observePendingCountForBook(serverId: String, itemId: String): Flow<Int> =
            rows.map { all -> all.count { it.serverId == serverId && it.itemId == itemId && it.updatedAt > it.lastSyncedAt } }

        override fun observePendingBookCountAcrossAll(): Flow<Int> =
            rows.map { all -> all.filter { it.updatedAt > it.lastSyncedAt }.distinctBy { it.serverId to it.itemId }.size }

        override suspend fun dirtyServerItems(): List<AnnotationDao.DirtyServerItem> =
            rows.value.filter { it.updatedAt > it.lastSyncedAt }
                .map { AnnotationDao.DirtyServerItem(it.serverId, it.itemId) }
                .distinct()

        override suspend fun markSynced(ids: List<String>, syncedAt: Long) {
            rows.value = rows.value.map { if (it.id in ids) it.copy(lastSyncedAt = syncedAt) else it }
        }

        override suspend fun purgeAgedTombstones(serverId: String, itemId: String, cutoff: Long): Int = 0
    }

    private class FakeDeviceIdStore(private val id: String) : DeviceIdStore {
        override suspend fun getOrCreate(): String = id
    }

    private fun buildStore(
        dao: AnnotationDao = FakeAnnotationDao(),
        deviceId: String = "device-A",
        clock: () -> Long = { 1234L },
        idGenerator: () -> String = { "uuid-fixed" },
    ) = AnnotationStoreImpl(dao, FakeDeviceIdStore(deviceId), clock, idGenerator)

    // Tracer bullet: createHighlight persists a yellow highlight carrying every sync-ready field.
    @Test
    fun `createHighlight persists a sync-ready yellow highlight`() = runTest {
        val dao = FakeAnnotationDao()
        val store = buildStore(dao = dao, deviceId = "device-A", clock = { 5000L }, idGenerator = { "uuid-1" })

        store.createHighlight(
            serverId = "abs1",
            itemId = "item1",
            cfi = "epubcfi(/6/4!/4/2,/1:0,/1:10)",
            textSnippet = "selected words",
            chapterHref = "chap01.xhtml",
        )

        val saved = dao.getById("uuid-1")!!
        assertEquals("abs1", saved.serverId)
        assertEquals("item1", saved.itemId)
        assertEquals(AnnotationEntity.TYPE_HIGHLIGHT, saved.type)
        assertEquals("epubcfi(/6/4!/4/2,/1:0,/1:10)", saved.cfi)
        assertEquals(AnnotationEntity.COLOR_YELLOW, saved.color)
        assertNull(saved.note)
        assertEquals("selected words", saved.textSnippet)
        assertEquals("chap01.xhtml", saved.chapterHref)
        assertEquals(5000L, saved.createdAt)
        assertEquals(5000L, saved.updatedAt)
        assertEquals("device-A", saved.originDeviceId)
        assertEquals("device-A", saved.lastModifiedByDeviceId)
        assertFalse(saved.deleted)
    }

    @Test
    fun `createHighlight returns the created annotation`() = runTest {
        val store = buildStore(idGenerator = { "uuid-1" })

        val created = store.createHighlight("abs1", "item1", "epubcfi(x)", "snip", "c.xhtml")

        assertEquals("uuid-1", created.id)
        assertEquals("epubcfi(x)", created.cfi)
        assertEquals(AnnotationEntity.COLOR_YELLOW, created.color)
    }

    @Test
    fun `observeHighlights emits non-deleted highlights for the item`() = runTest {
        val dao = FakeAnnotationDao()
        var n = 0
        val store = buildStore(dao = dao, idGenerator = { "id-${n++}" })

        store.createHighlight("abs1", "item1", "epubcfi(a)", "s", "c")
        store.createHighlight("abs1", "item2", "epubcfi(b)", "s", "c")

        val list = store.observeHighlights("abs1", "item1").first()
        assertEquals(1, list.size)
        assertEquals("item1", list[0].itemId)
    }

    @Test
    fun `delete tombstones the annotation so it leaves the live query`() = runTest {
        val dao = FakeAnnotationDao()
        val store = buildStore(dao = dao, deviceId = "device-A", clock = { 7000L }, idGenerator = { "uuid-1" })
        store.createHighlight("abs1", "item1", "epubcfi(a)", "s", "c")

        store.delete("uuid-1")

        assertTrue(store.observeHighlights("abs1", "item1").first().isEmpty())
        val tomb = dao.getById("uuid-1")!!
        assertTrue(tomb.deleted)
        assertEquals(7000L, tomb.updatedAt)
    }

    @Test
    fun `createBookmark persists a bookmark with correct type and empty color`() = runTest {
        val dao = FakeAnnotationDao()
        val store = buildStore(dao = dao, deviceId = "device-A", clock = { 9000L }, idGenerator = { "bm-1" })

        store.createBookmark(
            serverId = "abs1",
            itemId = "item1",
            cfi = "epubcfi(/6/4!/4/2)",
            textSnippet = "It seems increasingly likely",
            chapterHref = "chapter01.xhtml",
            spineIndex = 0,
            progression = 0.0,
            bookmarkTitle = "",
        )

        val saved = dao.getById("bm-1")!!
        assertEquals(AnnotationEntity.TYPE_BOOKMARK, saved.type)
        assertEquals("epubcfi(/6/4!/4/2)", saved.cfi)
        assertEquals("", saved.color)
        assertNull(saved.note)
        assertEquals("It seems increasingly likely", saved.textSnippet)
        assertEquals("chapter01.xhtml", saved.chapterHref)
        assertEquals(9000L, saved.createdAt)
        assertEquals(9000L, saved.updatedAt)
        assertEquals("device-A", saved.originDeviceId)
        assertEquals("device-A", saved.lastModifiedByDeviceId)
        assertFalse(saved.deleted)
    }

    @Test
    fun `createBookmark returns the created annotation`() = runTest {
        val store = buildStore(idGenerator = { "bm-1" })

        val created = store.createBookmark("abs1", "item1", "epubcfi(/6/4!/4/2)", "snip", "c.xhtml",
            spineIndex = 0, progression = 0.0, bookmarkTitle = "")

        assertEquals("bm-1", created.id)
        assertEquals(AnnotationEntity.TYPE_BOOKMARK, created.type)
    }

    @Test
    fun `observeBookmarks emits only bookmark-type annotations for the item`() = runTest {
        val dao = FakeAnnotationDao()
        var n = 0
        val store = buildStore(dao = dao, idGenerator = { "id-${n++}" })

        store.createHighlight("abs1", "item1", "epubcfi(a)", "h", "c")
        store.createBookmark("abs1", "item1", "epubcfi(b)", "snip", "c",
            spineIndex = 0, progression = 0.0, bookmarkTitle = "")
        store.createBookmark("abs1", "item2", "epubcfi(c)", "snip", "c",
            spineIndex = 0, progression = 0.0, bookmarkTitle = "")  // different item

        val list = store.observeBookmarks("abs1", "item1").first()
        assertEquals(1, list.size)
        assertEquals(AnnotationEntity.TYPE_BOOKMARK, list[0].type)
    }

    @Test
    fun `delete tombstones a bookmark so it leaves observeBookmarks`() = runTest {
        val dao = FakeAnnotationDao()
        val store = buildStore(dao = dao, idGenerator = { "bm-1" })
        store.createBookmark("abs1", "item1", "epubcfi(/6/4!/4/2)", "snip", "c",
            spineIndex = 0, progression = 0.0, bookmarkTitle = "")

        store.delete("bm-1")

        assertTrue(store.observeBookmarks("abs1", "item1").first().isEmpty())
    }

    @Test
    fun `observeHighlights does not include bookmarks`() = runTest {
        val dao = FakeAnnotationDao()
        var n = 0
        val store = buildStore(dao = dao, idGenerator = { "id-${n++}" })

        store.createHighlight("abs1", "item1", "epubcfi(a)", "h", "c")
        store.createBookmark("abs1", "item1", "epubcfi(b)", "snip", "c",
            spineIndex = 0, progression = 0.0, bookmarkTitle = "")

        val highlights = store.observeHighlights("abs1", "item1").first()
        assertEquals(1, highlights.size)
        assertEquals(AnnotationEntity.TYPE_HIGHLIGHT, highlights[0].type)
    }
}
