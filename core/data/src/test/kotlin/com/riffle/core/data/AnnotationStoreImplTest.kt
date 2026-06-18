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
import org.junit.Assert.assertTrue
import org.junit.Test

class AnnotationStoreImplTest {

    private val rows = MutableStateFlow<List<AnnotationEntity>>(emptyList())

    private val dao = object : AnnotationDao {
        override fun observeForItem(serverId: String, itemId: String): Flow<List<AnnotationEntity>> =
            rows.map { all -> all.filter { it.serverId == serverId && it.itemId == itemId && !it.deleted } }

        override suspend fun getForItem(serverId: String, itemId: String): List<AnnotationEntity> =
            rows.value.filter { it.serverId == serverId && it.itemId == itemId && !it.deleted }

        override suspend fun getById(id: String): AnnotationEntity? = rows.value.firstOrNull { it.id == id }

        override suspend fun upsert(entity: AnnotationEntity) {
            rows.value = rows.value.filterNot { it.id == entity.id } + entity
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
            rows.map { all ->
                all.filter { it.serverId == serverId && it.itemId == itemId && !it.deleted }
                    .sortedWith(compareBy({ it.spineIndex }, { it.progression }))
            }

        override suspend fun renameBookmark(id: String, title: String, updatedAt: Long, deviceId: String) {
            rows.value = rows.value.map {
                if (it.id == id) it.copy(bookmarkTitle = title, updatedAt = updatedAt, lastModifiedByDeviceId = deviceId) else it
            }
        }
    }

    private val deviceIdStore = object : DeviceIdStore {
        override suspend fun getOrCreate(): String = "device-X"
    }

    private var now = 1000L

    private fun store() = AnnotationStoreImpl(
        dao = dao,
        deviceIdStore = deviceIdStore,
        clock = { now },
        idGenerator = { "fixed-id" },
    )

    @Test
    fun `createHighlight persists the chosen colour token`() = runTest {
        val created = store().createHighlight(
            serverId = "abs1", itemId = "item1", cfi = "epubcfi(/6/4!/4/2,/1:0,/1:10)",
            textSnippet = "t", chapterHref = "c.xhtml", color = "green",
        )
        assertEquals("green", created.color)
        assertEquals("green", dao.getById(created.id)?.color)
    }

    @Test
    fun `recolor updates the colour and bumps updatedAt and device`() = runTest {
        val s = store()
        val created = s.createHighlight("abs1", "item1", "epubcfi(/6/4!/4/2,/1:0,/1:10)", "t", "c.xhtml")
        now = 5000L

        s.recolor(created.id, "blue")

        val row = dao.getById(created.id)
        assertEquals("blue", row?.color)
        assertEquals(5000L, row?.updatedAt)
        assertEquals("device-X", row?.lastModifiedByDeviceId)
    }

    @Test
    fun `updateNote adds a note and bumps updatedAt and device`() = runTest {
        val s = store()
        val created = s.createHighlight("abs1", "item1", "epubcfi(/6/4!/4/2,/1:0,/1:10)", "t", "c.xhtml")
        now = 7000L

        s.updateNote(created.id, "My note")

        val row = dao.getById(created.id)
        assertEquals("My note", row?.note)
        assertEquals(7000L, row?.updatedAt)
        assertEquals("device-X", row?.lastModifiedByDeviceId)
    }

    @Test
    fun `updateNote edits an existing note`() = runTest {
        val s = store()
        val created = s.createHighlight("abs1", "item1", "epubcfi(/6/4!/4/2,/1:0,/1:10)", "t", "c.xhtml")
        s.updateNote(created.id, "First")
        now = 8000L

        s.updateNote(created.id, "Revised")

        assertEquals("Revised", dao.getById(created.id)?.note)
        assertEquals(8000L, dao.getById(created.id)?.updatedAt)
    }

    @Test
    fun `updateNote with null clears the note and leaves the highlight intact`() = runTest {
        val s = store()
        val created = s.createHighlight("abs1", "item1", "epubcfi(/6/4!/4/2,/1:0,/1:10)", "t", "c.xhtml")
        s.updateNote(created.id, "To be removed")

        s.updateNote(created.id, null)

        val row = dao.getById(created.id)
        assertEquals(null, row?.note)
        assertEquals(false, row?.deleted)
        assertEquals("t", row?.textSnippet)
    }

    @Test
    fun `tombstoned highlights are excluded from observeHighlights (and thus from rendering)`() = runTest {
        val s = store()
        val created = s.createHighlight("abs1", "item1", "epubcfi(/6/4!/4/2,/1:0,/1:10)", "t", "c.xhtml")
        assertEquals(1, s.observeHighlights("abs1", "item1").first().size)

        s.delete(created.id)

        assertTrue(s.observeHighlights("abs1", "item1").first().isEmpty())
    }
}
