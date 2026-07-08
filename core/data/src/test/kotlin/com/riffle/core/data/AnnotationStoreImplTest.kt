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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AnnotationStoreImplTest {

    private val rows = MutableStateFlow<List<AnnotationEntity>>(emptyList())

    private val dao = object : AnnotationDao {
        override fun observeForItem(sourceId: String, itemId: String): Flow<List<AnnotationEntity>> =
            rows.map { all -> all.filter { it.sourceId == sourceId && it.itemId == itemId && !it.deleted } }

        override suspend fun getForItem(sourceId: String, itemId: String): List<AnnotationEntity> =
            rows.value.filter { it.sourceId == sourceId && it.itemId == itemId && !it.deleted }

        override suspend fun getAllForItemIncludingDeleted(sourceId: String, itemId: String): List<AnnotationEntity> =
            rows.value.filter { it.sourceId == sourceId && it.itemId == itemId }

        override suspend fun getById(id: String): AnnotationEntity? = rows.value.firstOrNull { it.id == id }

        override suspend fun getByItemAndCfi(sourceId: String, itemId: String, cfi: String): AnnotationEntity? =
            rows.value.firstOrNull { it.sourceId == sourceId && it.itemId == itemId && it.cfi == cfi && !it.deleted }

        override suspend fun findImageForFigure(
            sourceId: String,
            itemId: String,
            chapterHref: String,
            imageHref: String?,
            imageSvg: String?,
        ): AnnotationEntity? = rows.value.firstOrNull {
            it.sourceId == sourceId && it.itemId == itemId && it.chapterHref == chapterHref &&
                it.type == AnnotationEntity.TYPE_IMAGE && !it.deleted &&
                (imageHref == null || it.imageHref == imageHref) &&
                (imageSvg == null || it.imageSvg == imageSvg)
        }

        override suspend fun upsert(entity: AnnotationEntity) {
            rows.value = rows.value.filterNot { it.id == entity.id } + entity
        }

        override suspend fun upsertAll(annotations: List<AnnotationEntity>) {
            val idsToReplace = annotations.map { it.id }.toSet()
            rows.value = rows.value.filterNot { it.id in idsToReplace } + annotations
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

        override fun observeAnnotationsByPosition(sourceId: String, itemId: String): Flow<List<AnnotationEntity>> =
            rows.map { all ->
                all.filter { it.sourceId == sourceId && it.itemId == itemId && !it.deleted }
                    .sortedWith(compareBy({ it.spineIndex }, { it.progression }))
            }

        override suspend fun renameBookmark(id: String, title: String, updatedAt: Long, deviceId: String) {
            rows.value = rows.value.map {
                if (it.id == id) it.copy(bookmarkTitle = title, updatedAt = updatedAt, lastModifiedByDeviceId = deviceId) else it
            }
        }

        override fun observeForSource(sourceId: String): Flow<List<AnnotationEntity>> =
            rows.map { all -> all.filter { it.sourceId == sourceId && !it.deleted } }

        override fun observePendingCountForBook(sourceId: String, itemId: String): Flow<Int> =
            rows.map { all -> all.count { it.sourceId == sourceId && it.itemId == itemId && it.updatedAt > it.lastSyncedAt } }

        override fun observePendingBookCountAcrossAll(): Flow<Int> =
            rows.map { all -> all.filter { it.updatedAt > it.lastSyncedAt }.distinctBy { it.sourceId to it.itemId }.size }

        override suspend fun dirtySourceItems(): List<AnnotationDao.DirtySourceItem> =
            rows.value.filter { it.updatedAt > it.lastSyncedAt }
                .map { AnnotationDao.DirtySourceItem(it.sourceId, it.itemId) }
                .distinct()

        override suspend fun markSynced(ids: List<String>, syncedAt: Long) {
            rows.value = rows.value.map { if (it.id in ids) it.copy(lastSyncedAt = syncedAt) else it }
        }

        override suspend fun purgeAgedTombstones(sourceId: String, itemId: String, cutoff: Long): Int = 0

        override fun observeBooksWithHighlights(sourceId: String): Flow<List<com.riffle.core.database.BookHighlightSummary>> =
            kotlinx.coroutines.flow.flowOf(emptyList())
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
            sourceId = "abs1", itemId = "item1", cfi = "epubcfi(/6/4!/4/2,/1:0,/1:10)",
            textSnippet = "t", chapterHref = "c.xhtml", color = "green",
        )
        assertEquals("green", created.color)
        assertEquals("green", dao.getById(created.id)?.color)
    }

    @Test
    fun `findByItemAndCfi returns the matching live annotation`() = runTest {
        val s = store()
        val cfi = "epubcfi(/6/4!/4/26[s3],/1:0,/1:24)"
        val created = s.createHighlight("abs1", "item1", cfi, "Section 1.3", "c.xhtml")
        val found = s.findByItemAndCfi("abs1", "item1", cfi)
        assertEquals(created.id, found?.id)
    }

    @Test
    fun `findByItemAndCfi returns null on CFI mismatch`() = runTest {
        val s = store()
        s.createHighlight("abs1", "item1", "epubcfi(/6/4!/4/26[s3],/1:0,/1:24)", "t", "c.xhtml")
        assertNull(s.findByItemAndCfi("abs1", "item1", "epubcfi(/6/4!/4/22,/1:0,/1:5)"))
    }

    @Test
    fun `findByItemAndCfi scopes by sourceId and itemId`() = runTest {
        val s = store()
        val cfi = "epubcfi(/6/4!/4/2,/1:0,/1:10)"
        s.createHighlight("abs1", "item1", cfi, "t", "c.xhtml")
        // Same CFI but different source / item — must NOT match.
        assertNull(s.findByItemAndCfi("abs2", "item1", cfi))
        assertNull(s.findByItemAndCfi("abs1", "item2", cfi))
    }

    @Test
    fun `findByItemAndCfi skips tombstoned annotations`() = runTest {
        val s = store()
        val cfi = "epubcfi(/6/4!/4/2,/1:0,/1:10)"
        val created = s.createHighlight("abs1", "item1", cfi, "t", "c.xhtml")
        s.delete(created.id) // tombstones the row
        assertNull(s.findByItemAndCfi("abs1", "item1", cfi))
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

    @Test
    fun `createBookmark stores spineIndex, progression and bookmarkTitle`() = runTest {
        val created = store().createBookmark(
            sourceId = "abs1", itemId = "item1",
            cfi = "epubcfi(/6/6!/4/1:0)",
            textSnippet = "", chapterHref = "ch2.xhtml",
            spineIndex = 3, progression = 0.42, bookmarkTitle = "The Egg · 42%",
        )
        assertEquals(3, created.spineIndex)
        assertEquals(0.42, created.progression, 0.001)
        assertEquals("The Egg · 42%", created.bookmarkTitle)
    }

    @Test
    fun `renameBookmark updates the title in the store`() = runTest {
        val s = store()
        val created = s.createBookmark(
            "abs1", "item1", "epubcfi(/6/6!/4/1:0)", "", "ch2.xhtml",
            spineIndex = 0, progression = 0.0, bookmarkTitle = "42%",
        )
        s.renameBookmark(created.id, "Where it gets weird")
        val updated = dao.getById(created.id)
        assertEquals("Where it gets weird", updated?.bookmarkTitle)
    }

    @Test
    fun `observeAnnotations returns highlights and bookmarks sorted by spineIndex then progression`() = runTest {
        var idCounter = 0
        fun storeWithUniqueIds() = AnnotationStoreImpl(
            dao = dao,
            deviceIdStore = deviceIdStore,
            clock = { now },
            idGenerator = { "id-${idCounter++}" },
        )
        val s = storeWithUniqueIds()
        // Insert out-of-order
        s.createHighlight("abs1", "item1", "cfi1", "text", "ch3.xhtml",
            textBefore = "", textAfter = "")
            .also { rows.value = rows.value.map { e -> if (e.id == it.id) e.copy(spineIndex = 2, progression = 0.1) else e } }
        s.createBookmark("abs1", "item1", "cfi2", "", "ch1.xhtml",
            spineIndex = 0, progression = 0.9, bookmarkTitle = "bm1")
        s.createHighlight("abs1", "item1", "cfi3", "text2", "ch1.xhtml",
            textBefore = "", textAfter = "")
            .also { rows.value = rows.value.map { e -> if (e.id == it.id) e.copy(spineIndex = 0, progression = 0.2) else e } }

        val result = storeWithUniqueIds().observeAnnotations("abs1", "item1").first()
        // ch1 progression=0.2, ch1 progression=0.9, ch3 progression=0.1
        assertEquals(3, result.size)
        assertEquals(0, result[0].spineIndex); assertEquals(0.2, result[0].progression, 0.001)
        assertEquals(0, result[1].spineIndex); assertEquals(0.9, result[1].progression, 0.001)
        assertEquals(2, result[2].spineIndex)
    }

    @Test
    fun `observeAnnotations excludes tombstoned annotations`() = runTest {
        val s = store()
        val bm = s.createBookmark("abs1", "item1", "cfi", "", "ch.xhtml",
            spineIndex = 0, progression = 0.0, bookmarkTitle = "x")
        s.delete(bm.id)
        val result = s.observeAnnotations("abs1", "item1").first()
        assertTrue(result.isEmpty())
    }

    @Test
    fun observeAnnotationsForServerReturnsAllNonDeletedForServer() = runTest {
        var idSeq = 0
        val store = AnnotationStoreImpl(dao, deviceIdStore, clock = { 0L }, idGenerator = { "id-${idSeq++}" })
        store.createHighlight("srv1", "b1", "epubcfi(/6/4!/4)", "snippet", "ch.html")
        store.createBookmark("srv1", "b2", "epubcfi(/6/6!/2)", "top", "ch2.html", 1, 0.1, "mark")
        store.createHighlight("srv2", "b9", "epubcfi(/6/4!/4)", "other source", "ch.html")

        val forSrv1 = store.observeAnnotationsForSource("srv1").first()
        assertEquals(setOf("b1", "b2"), forSrv1.map { it.itemId }.toSet())
    }

    @Test
    fun `findImageAnnotationForFigure finds an existing image annotation by href`() = runTest {
        val s = store()
        val created = s.createImageAnnotation(
            sourceId = "abs1", itemId = "item1", cfi = "epubcfi(/6/4!/4/2:0)", textSnippet = "Fig 1",
            chapterHref = "ch1.xhtml", spineIndex = 0, progression = 0.5,
            imageHref = "images/g.png", imageSvg = null,
            imageBytes = null,
        )
        val found = s.findImageAnnotationForFigure(
            sourceId = "abs1", itemId = "item1", chapterHref = "ch1.xhtml",
            imageHref = "images/g.png", imageSvg = null,)
        assertEquals(created.id, found?.id)
    }

    @Test
    fun `findImageAnnotationForFigure finds an existing image annotation by svg`() = runTest {
        val s = store()
        val created = s.createImageAnnotation(
            sourceId = "abs1", itemId = "item1", cfi = "epubcfi(/6/4!/4/2:0)", textSnippet = "Diagram",
            chapterHref = "ch1.xhtml", spineIndex = 0, progression = 0.5,
            imageHref = null, imageSvg = "<svg><rect/></svg>",
            imageBytes = null,
        )
        val found = s.findImageAnnotationForFigure(
            sourceId = "abs1", itemId = "item1", chapterHref = "ch1.xhtml",
            imageHref = null, imageSvg = "<svg><rect/></svg>",)
        assertEquals(created.id, found?.id)
    }

    @Test
    fun `findImageAnnotationForFigure returns null when no annotation matches this figure`() = runTest {
        val s = store()
        s.createImageAnnotation(
            sourceId = "abs1", itemId = "item1", cfi = "epubcfi(/6/4!/4/2:0)", textSnippet = "Fig 1",
            chapterHref = "ch1.xhtml", spineIndex = 0, progression = 0.5,
            imageHref = "images/g.png", imageSvg = null,
            imageBytes = null,
        )
        val found = s.findImageAnnotationForFigure(
            sourceId = "abs1", itemId = "item1", chapterHref = "ch1.xhtml",
            imageHref = "images/other.png", imageSvg = null,)
        assertNull(found)
    }

    @Test
    fun `findImageAnnotationForFigure ignores soft-deleted rows`() = runTest {
        val s = store()
        val created = s.createImageAnnotation(
            sourceId = "abs1", itemId = "item1", cfi = "epubcfi(/6/4!/4/2:0)", textSnippet = "Fig 1",
            chapterHref = "ch1.xhtml", spineIndex = 0, progression = 0.5,
            imageHref = "images/g.png", imageSvg = null,
            imageBytes = null,
        )
        s.delete(created.id)
        val found = s.findImageAnnotationForFigure(
            sourceId = "abs1", itemId = "item1", chapterHref = "ch1.xhtml",
            imageHref = "images/g.png", imageSvg = null,)
        assertNull(found)
    }
}
