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
        override suspend fun getById(id: String): AnnotationEntity? = rows.value.firstOrNull { it.id == id }
        override suspend fun upsert(entity: AnnotationEntity) {
            rows.value = rows.value.filterNot { it.id == entity.id } + entity
        }
        override suspend fun tombstone(id: String, updatedAt: Long, deviceId: String) {
            rows.value = rows.value.map {
                if (it.id == id) it.copy(deleted = true, updatedAt = updatedAt, lastModifiedByDeviceId = deviceId) else it
            }
        }
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
}
