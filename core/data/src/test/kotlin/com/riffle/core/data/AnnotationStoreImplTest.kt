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

private const val TEST_FONT = "Georgia, serif"

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
                if (it.id == id) it.copy(
                    deleted = true,
                    updatedAt = maxOf(it.updatedAt + 1, updatedAt),
                    lastModifiedByDeviceId = deviceId,
                ) else it
            }
        }

        override suspend fun recolor(id: String, color: String, updatedAt: Long, deviceId: String) {
            rows.value = rows.value.map {
                if (it.id == id) it.copy(
                    color = color,
                    updatedAt = maxOf(it.updatedAt + 1, updatedAt),
                    lastModifiedByDeviceId = deviceId,
                ) else it
            }
        }

        override suspend fun backfillNullOriginFontFamily(
            sourceId: String,
            itemId: String,
            fontFamily: String,
            updatedAt: Long,
            deviceId: String,
        ): Int {
            var updated = 0
            rows.value = rows.value.map { row ->
                if (row.sourceId == sourceId && row.itemId == itemId && !row.deleted && row.originFontFamily == null) {
                    updated++
                    row.copy(
                        originFontFamily = fontFamily,
                        updatedAt = updatedAt,
                        lastModifiedByDeviceId = deviceId,
                    )
                } else row
            }
            return updated
        }

        override suspend fun healSentinelOriginFontFamily(
            sourceId: String,
            itemId: String,
            sentinel: String,
            fontFamily: String,
            updatedAt: Long,
            deviceId: String,
        ): Int = 0


        override suspend fun updateNote(id: String, note: String?, updatedAt: Long, deviceId: String) {
            rows.value = rows.value.map {
                if (it.id == id) it.copy(
                    note = note,
                    updatedAt = maxOf(it.updatedAt + 1, updatedAt),
                    lastModifiedByDeviceId = deviceId,
                ) else it
            }
        }

        override fun observeAnnotationsByPosition(sourceId: String, itemId: String): Flow<List<AnnotationEntity>> =
            rows.map { all ->
                all.filter { it.sourceId == sourceId && it.itemId == itemId && !it.deleted }
                    .sortedWith(compareBy({ it.spineIndex }, { it.progression }))
            }

        override suspend fun renameBookmark(id: String, title: String, updatedAt: Long, deviceId: String) {
            rows.value = rows.value.map {
                if (it.id == id) it.copy(
                    bookmarkTitle = title,
                    updatedAt = maxOf(it.updatedAt + 1, updatedAt),
                    lastModifiedByDeviceId = deviceId,
                ) else it
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

        override suspend fun updateEmphasisStyles(
            id: String,
            emphasisStyles: String,
            updatedAt: Long,
            deviceId: String,
        ): Int {
            var updated = 0
            rows.value = rows.value.map {
                if (it.id == id && it.type == AnnotationEntity.TYPE_EMPHASIS && !it.deleted) {
                    updated++
                    it.copy(
                        emphasisStyles = emphasisStyles,
                        updatedAt = maxOf(it.updatedAt + 1, updatedAt),
                        lastModifiedByDeviceId = deviceId,
                    )
                } else it
            }
            return updated
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
            sourceId = "abs1", itemId = "item1", cfi = "epubcfi(/6/4!/4/2,/1:0,/1:10)",
            textSnippet = "t", chapterHref = "c.xhtml", color = "green",
            originFontFamily = TEST_FONT,
        )
        assertEquals("green", created.color)
        assertEquals("green", dao.getById(created.id)?.color)
    }

    // Issue #484: `originFontFamily` must round-trip from the createHighlight/createBookmark call
    // to the persisted entity — non-null contract on the local write path.
    @Test
    fun `createHighlight persists originFontFamily to the entity`() = runTest {
        val created = store().createHighlight(
            sourceId = "abs1", itemId = "item1", cfi = "epubcfi(/6/4!/4/2,/1:0,/1:10)",
            textSnippet = "t", chapterHref = "c.xhtml",
            originFontFamily = "\"Fira Sans\", sans-serif",
        )
        assertEquals("\"Fira Sans\", sans-serif", created.let { dao.getById(it.id)?.originFontFamily })
    }

    @Test
    fun `createBookmark persists originFontFamily to the entity`() = runTest {
        val created = store().createBookmark(
            sourceId = "abs1", itemId = "item1", cfi = "epubcfi(/6/6!/4/1:0)",
            textSnippet = "", chapterHref = "ch2.xhtml",
            spineIndex = 0, progression = 0.0, bookmarkTitle = "x",
            originFontFamily = "\"Merriweather\", serif",
        )
        assertEquals("\"Merriweather\", serif", dao.getById(created.id)?.originFontFamily)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `createHighlight rejects blank originFontFamily`() = runTest {
        store().createHighlight(
            sourceId = "abs1", itemId = "item1", cfi = "epubcfi(/6/4!/4/2,/1:0,/1:10)",
            textSnippet = "t", chapterHref = "c.xhtml",
            originFontFamily = "  ",
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun `createBookmark rejects blank originFontFamily`() = runTest {
        store().createBookmark(
            sourceId = "abs1", itemId = "item1", cfi = "epubcfi(/6/6!/4/1:0)",
            textSnippet = "", chapterHref = "c.xhtml",
            spineIndex = 0, progression = 0.0, bookmarkTitle = "",
            originFontFamily = "",
        )
    }

    @Test
    fun `findByItemAndCfi returns the matching live annotation`() = runTest {
        val s = store()
        val cfi = "epubcfi(/6/4!/4/26[s3],/1:0,/1:24)"
        val created = s.createHighlight("abs1", "item1", cfi, "Section 1.3", "c.xhtml", originFontFamily = TEST_FONT)
        val found = s.findByItemAndCfi("abs1", "item1", cfi)
        assertEquals(created.id, found?.id)
    }

    @Test
    fun `findByItemAndCfi returns null on CFI mismatch`() = runTest {
        val s = store()
        s.createHighlight("abs1", "item1", "epubcfi(/6/4!/4/26[s3],/1:0,/1:24)", "t", "c.xhtml", originFontFamily = TEST_FONT)
        assertNull(s.findByItemAndCfi("abs1", "item1", "epubcfi(/6/4!/4/22,/1:0,/1:5)"))
    }

    @Test
    fun `findByItemAndCfi scopes by sourceId and itemId`() = runTest {
        val s = store()
        val cfi = "epubcfi(/6/4!/4/2,/1:0,/1:10)"
        s.createHighlight("abs1", "item1", cfi, "t", "c.xhtml", originFontFamily = TEST_FONT)
        // Same CFI but different source / item — must NOT match.
        assertNull(s.findByItemAndCfi("abs2", "item1", cfi))
        assertNull(s.findByItemAndCfi("abs1", "item2", cfi))
    }

    @Test
    fun `findByItemAndCfi skips tombstoned annotations`() = runTest {
        val s = store()
        val cfi = "epubcfi(/6/4!/4/2,/1:0,/1:10)"
        val created = s.createHighlight("abs1", "item1", cfi, "t", "c.xhtml", originFontFamily = TEST_FONT)
        s.delete(created.id) // tombstones the row
        assertNull(s.findByItemAndCfi("abs1", "item1", cfi))
    }

    @Test
    fun `recolor updates the colour and bumps updatedAt and device`() = runTest {
        val s = store()
        val created = s.createHighlight("abs1", "item1", "epubcfi(/6/4!/4/2,/1:0,/1:10)", "t", "c.xhtml", originFontFamily = TEST_FONT)
        now = 5000L

        s.recolor(created.id, "blue")

        val row = dao.getById(created.id)
        assertEquals("blue", row?.color)
        assertEquals(5000L, row?.updatedAt)
        assertEquals("device-X", row?.lastModifiedByDeviceId)
    }

    // Regression: annotation edits sync via LWW on updatedAt. A peer file (or a prior remote merge
    // that adopted a peer's future stamp) can leave a row with updatedAt ahead of this device's
    // wall clock. Without the atomic MAX(updatedAt+1, :updatedAt) guard in AnnotationDao.recolor,
    // the local edit stamps updatedAt = clock() ≤ peer stamp, and the next AnnotationLiveSync tick
    // upserts the peer's old color back over the recolor — the "color reverts" bug.
    @Test
    fun `recolor stamps strictly newer than an inherited future updatedAt (revert-race guard)`() = runTest {
        val s = store()
        val created = s.createHighlight(
            "abs1", "item1", "epubcfi(/6/4!/4/2,/1:0,/1:10)", "t", "c.xhtml",
            originFontFamily = TEST_FONT,
        )
        // Peer merge landed a future stamp on this row (wall clock skew or adopted-peer stamp).
        rows.value = rows.value.map {
            if (it.id == created.id) it.copy(updatedAt = 999_999L) else it
        }
        // Local clock is behind the peer stamp.
        now = 5000L

        s.recolor(created.id, "blue")

        val row = dao.getById(created.id)
        assertEquals("blue", row?.color)
        assertTrue(
            "updatedAt must be strictly greater than the pre-existing stamp; was ${row?.updatedAt}",
            (row?.updatedAt ?: 0L) > 999_999L,
        )
    }

    @Test
    fun `updateNote stamps strictly newer than an inherited future updatedAt (revert-race guard)`() = runTest {
        val s = store()
        val created = s.createHighlight(
            "abs1", "item1", "epubcfi(/6/4!/4/2,/1:0,/1:10)", "t", "c.xhtml",
            originFontFamily = TEST_FONT,
        )
        rows.value = rows.value.map {
            if (it.id == created.id) it.copy(updatedAt = 999_999L) else it
        }
        now = 5000L

        s.updateNote(created.id, "Fresh note")

        val row = dao.getById(created.id)
        assertEquals("Fresh note", row?.note)
        assertTrue((row?.updatedAt ?: 0L) > 999_999L)
    }

    @Test
    fun `delete tombstone stamps strictly newer than an inherited future updatedAt (revert-race guard)`() = runTest {
        val s = store()
        val created = s.createHighlight(
            "abs1", "item1", "epubcfi(/6/4!/4/2,/1:0,/1:10)", "t", "c.xhtml",
            originFontFamily = TEST_FONT,
        )
        rows.value = rows.value.map {
            if (it.id == created.id) it.copy(updatedAt = 999_999L) else it
        }
        now = 5000L

        s.delete(created.id)

        val row = dao.getById(created.id)
        assertEquals(true, row?.deleted)
        assertTrue((row?.updatedAt ?: 0L) > 999_999L)
    }

    @Test
    fun `renameBookmark stamps strictly newer than an inherited future updatedAt (revert-race guard)`() = runTest {
        val s = store()
        val created = s.createBookmark(
            "abs1", "item1", "epubcfi(/6/6!/4/1:0)", "", "ch2.xhtml",
            spineIndex = 0, progression = 0.0, bookmarkTitle = "old",
            originFontFamily = TEST_FONT,
        )
        rows.value = rows.value.map {
            if (it.id == created.id) it.copy(updatedAt = 999_999L) else it
        }
        now = 5000L

        s.renameBookmark(created.id, "renamed")

        val row = dao.getById(created.id)
        assertEquals("renamed", row?.bookmarkTitle)
        assertTrue((row?.updatedAt ?: 0L) > 999_999L)
    }

    @Test
    fun `updateNote adds a note and bumps updatedAt and device`() = runTest {
        val s = store()
        val created = s.createHighlight("abs1", "item1", "epubcfi(/6/4!/4/2,/1:0,/1:10)", "t", "c.xhtml", originFontFamily = TEST_FONT)
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
        val created = s.createHighlight("abs1", "item1", "epubcfi(/6/4!/4/2,/1:0,/1:10)", "t", "c.xhtml", originFontFamily = TEST_FONT)
        s.updateNote(created.id, "First")
        now = 8000L

        s.updateNote(created.id, "Revised")

        assertEquals("Revised", dao.getById(created.id)?.note)
        assertEquals(8000L, dao.getById(created.id)?.updatedAt)
    }

    @Test
    fun `updateNote with null clears the note and leaves the highlight intact`() = runTest {
        val s = store()
        val created = s.createHighlight("abs1", "item1", "epubcfi(/6/4!/4/2,/1:0,/1:10)", "t", "c.xhtml", originFontFamily = TEST_FONT)
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
        val created = s.createHighlight("abs1", "item1", "epubcfi(/6/4!/4/2,/1:0,/1:10)", "t", "c.xhtml", originFontFamily = TEST_FONT)
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
            originFontFamily = TEST_FONT,
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
            originFontFamily = TEST_FONT,
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
            textBefore = "", textAfter = "", originFontFamily = TEST_FONT)
            .also { rows.value = rows.value.map { e -> if (e.id == it.id) e.copy(spineIndex = 2, progression = 0.1) else e } }
        s.createBookmark("abs1", "item1", "cfi2", "", "ch1.xhtml",
            spineIndex = 0, progression = 0.9, bookmarkTitle = "bm1", originFontFamily = TEST_FONT)
        s.createHighlight("abs1", "item1", "cfi3", "text2", "ch1.xhtml",
            textBefore = "", textAfter = "", originFontFamily = TEST_FONT)
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
            spineIndex = 0, progression = 0.0, bookmarkTitle = "x", originFontFamily = TEST_FONT)
        s.delete(bm.id)
        val result = s.observeAnnotations("abs1", "item1").first()
        assertTrue(result.isEmpty())
    }

    @Test
    fun observeAnnotationsForServerReturnsAllNonDeletedForServer() = runTest {
        var idSeq = 0
        val store = AnnotationStoreImpl(dao, deviceIdStore, clock = { 0L }, idGenerator = { "id-${idSeq++}" })
        store.createHighlight("srv1", "b1", "epubcfi(/6/4!/4)", "snippet", "ch.html", originFontFamily = TEST_FONT)
        store.createBookmark("srv1", "b2", "epubcfi(/6/6!/2)", "top", "ch2.html", 1, 0.1, "mark", originFontFamily = TEST_FONT)
        store.createHighlight("srv2", "b9", "epubcfi(/6/4!/4)", "other source", "ch.html", originFontFamily = TEST_FONT)

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
