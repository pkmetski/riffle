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

private const val TEST_FONT = "Georgia, serif"

class AnnotationStoreTest {

    private class FakeAnnotationDao : AnnotationDao {
        val rows = MutableStateFlow<List<AnnotationEntity>>(emptyList())
        override fun observeForItem(sourceId: String, itemId: String): Flow<List<AnnotationEntity>> =
            rows.map { list ->
                list.filter { it.sourceId == sourceId && it.itemId == itemId && !it.deleted }
                    .sortedBy { it.createdAt }
            }
        override suspend fun getForItem(sourceId: String, itemId: String): List<AnnotationEntity> =
            rows.value.filter { it.sourceId == sourceId && it.itemId == itemId && !it.deleted }
                .sortedBy { it.createdAt }
        override suspend fun getAllForItemIncludingDeleted(sourceId: String, itemId: String): List<AnnotationEntity> =
            rows.value.filter { it.sourceId == sourceId && it.itemId == itemId }
                .sortedBy { it.createdAt }
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
                if (it.id == id) it.copy(note = note, updatedAt = updatedAt, lastModifiedByDeviceId = deviceId) else it
            }
        }

        override fun observeAnnotationsByPosition(sourceId: String, itemId: String): Flow<List<AnnotationEntity>> =
            rows.map { list ->
                list.filter { it.sourceId == sourceId && it.itemId == itemId && !it.deleted }
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
        override fun observeBooksWithHighlights(sourceId: String): Flow<List<com.riffle.core.database.BookHighlightSummary>> = kotlinx.coroutines.flow.flowOf(emptyList())

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
                        updatedAt = updatedAt,
                        lastModifiedByDeviceId = deviceId,
                    )
                } else it
            }
            return updated
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
            sourceId = "abs1",
            itemId = "item1",
            cfi = "epubcfi(/6/4!/4/2,/1:0,/1:10)",
            textSnippet = "selected words",
            chapterHref = "chap01.xhtml",
            originFontFamily = TEST_FONT,
        )

        val saved = dao.getById("uuid-1")!!
        assertEquals("abs1", saved.sourceId)
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

        val created = store.createHighlight("abs1", "item1", "epubcfi(x)", "snip", "c.xhtml", originFontFamily = TEST_FONT)

        assertEquals("uuid-1", created.id)
        assertEquals("epubcfi(x)", created.cfi)
        assertEquals(AnnotationEntity.COLOR_YELLOW, created.color)
    }

    @Test
    fun `observeHighlights emits non-deleted highlights for the item`() = runTest {
        val dao = FakeAnnotationDao()
        var n = 0
        val store = buildStore(dao = dao, idGenerator = { "id-${n++}" })

        store.createHighlight("abs1", "item1", "epubcfi(a)", "s", "c", originFontFamily = TEST_FONT)
        store.createHighlight("abs1", "item2", "epubcfi(b)", "s", "c", originFontFamily = TEST_FONT)

        val list = store.observeHighlights("abs1", "item1").first()
        assertEquals(1, list.size)
        assertEquals("item1", list[0].itemId)
    }

    @Test
    fun `delete tombstones the annotation so it leaves the live query`() = runTest {
        val dao = FakeAnnotationDao()
        val store = buildStore(dao = dao, deviceId = "device-A", clock = { 7000L }, idGenerator = { "uuid-1" })
        store.createHighlight("abs1", "item1", "epubcfi(a)", "s", "c", originFontFamily = TEST_FONT)

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
            sourceId = "abs1",
            itemId = "item1",
            cfi = "epubcfi(/6/4!/4/2)",
            textSnippet = "It seems increasingly likely",
            chapterHref = "chapter01.xhtml",
            spineIndex = 0,
            progression = 0.0,
            bookmarkTitle = "",
            originFontFamily = TEST_FONT,
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
            spineIndex = 0, progression = 0.0, bookmarkTitle = "", originFontFamily = TEST_FONT)

        assertEquals("bm-1", created.id)
        assertEquals(AnnotationEntity.TYPE_BOOKMARK, created.type)
    }

    @Test
    fun `observeBookmarks emits only bookmark-type annotations for the item`() = runTest {
        val dao = FakeAnnotationDao()
        var n = 0
        val store = buildStore(dao = dao, idGenerator = { "id-${n++}" })

        store.createHighlight("abs1", "item1", "epubcfi(a)", "h", "c", originFontFamily = TEST_FONT)
        store.createBookmark("abs1", "item1", "epubcfi(b)", "snip", "c",
            spineIndex = 0, progression = 0.0, bookmarkTitle = "", originFontFamily = TEST_FONT)
        store.createBookmark("abs1", "item2", "epubcfi(c)", "snip", "c",
            spineIndex = 0, progression = 0.0, bookmarkTitle = "", originFontFamily = TEST_FONT)  // different item

        val list = store.observeBookmarks("abs1", "item1").first()
        assertEquals(1, list.size)
        assertEquals(AnnotationEntity.TYPE_BOOKMARK, list[0].type)
    }

    @Test
    fun `delete tombstones a bookmark so it leaves observeBookmarks`() = runTest {
        val dao = FakeAnnotationDao()
        val store = buildStore(dao = dao, idGenerator = { "bm-1" })
        store.createBookmark("abs1", "item1", "epubcfi(/6/4!/4/2)", "snip", "c",
            spineIndex = 0, progression = 0.0, bookmarkTitle = "", originFontFamily = TEST_FONT)

        store.delete("bm-1")

        assertTrue(store.observeBookmarks("abs1", "item1").first().isEmpty())
    }

    @Test
    fun `observeHighlights does not include bookmarks`() = runTest {
        val dao = FakeAnnotationDao()
        var n = 0
        val store = buildStore(dao = dao, idGenerator = { "id-${n++}" })

        store.createHighlight("abs1", "item1", "epubcfi(a)", "h", "c", originFontFamily = TEST_FONT)
        store.createBookmark("abs1", "item1", "epubcfi(b)", "snip", "c",
            spineIndex = 0, progression = 0.0, bookmarkTitle = "", originFontFamily = TEST_FONT)

        val highlights = store.observeHighlights("abs1", "item1").first()
        assertEquals(1, highlights.size)
        assertEquals(AnnotationEntity.TYPE_HIGHLIGHT, highlights[0].type)
    }

    // ADR 0046: TYPE_EMPHASIS row carries a non-empty styles set encoded as the wire form.
    // The regression flip: reverting createEmphasis would either leave `emphasisStyles` NULL
    // (row created but styleless) or persist the wrong type constant.
    @Test
    fun `createEmphasis persists a TYPE_EMPHASIS row with encoded styles`() = runTest {
        val dao = FakeAnnotationDao()
        val store = buildStore(dao = dao, deviceId = "device-A", clock = { 7000L }, idGenerator = { "uuid-em" })

        store.createEmphasis(
            sourceId = "abs1",
            itemId = "item1",
            cfi = "epubcfi(/6/4!/4/2,/1:0,/1:10)",
            textSnippet = "the key phrase",
            chapterHref = "chap01.xhtml",
            styles = setOf(com.riffle.core.domain.EmphasisStyle.BOLD, com.riffle.core.domain.EmphasisStyle.UNDERLINE),
            originFontFamily = TEST_FONT,
        )

        val saved = dao.getById("uuid-em")!!
        assertEquals(AnnotationEntity.TYPE_EMPHASIS, saved.type)
        assertEquals("bold,underline", saved.emphasisStyles)
        assertEquals("", saved.color)
        assertEquals("the key phrase", saved.textSnippet)
        assertEquals("device-A", saved.originDeviceId)
        assertEquals(7000L, saved.createdAt)
    }

    // Empty styles is a caller error — the ViewModel gates on non-empty before invoking the store.
    // A regression that let the store persist an empty-styles row would create a shadow annotation
    // the renderer can't paint and the merge logic can't equate.
    @Test(expected = IllegalArgumentException::class)
    fun `createEmphasis rejects an empty styles set`() = runTest {
        val store = buildStore()
        store.createEmphasis(
            sourceId = "abs1",
            itemId = "item1",
            cfi = "epubcfi(/6/4!/4/2,/1:0,/1:10)",
            textSnippet = "phrase",
            chapterHref = "chap01.xhtml",
            styles = emptySet(),
            originFontFamily = TEST_FONT,
        )
    }

    // Toggle-off from partial selection is a range shrink at the reader layer; here we only
    // cover the styles mutation (the single-row edit that the ViewModel invokes when the whole
    // selection toggles). Regression flip: the DAO's `type = 'EMPHASIS'` guard is what protects
    // a highlight from being clobbered if a caller mis-routes here.
    @Test
    fun `updateEmphasisStyles rewrites styles and bumps updatedAt+provenance`() = runTest {
        val dao = FakeAnnotationDao()
        val store = buildStore(dao = dao, deviceId = "device-A", clock = { 7000L }, idGenerator = { "uuid-em" })

        store.createEmphasis(
            sourceId = "abs1",
            itemId = "item1",
            cfi = "epubcfi(/6/4!/4/2,/1:0,/1:10)",
            textSnippet = "the key phrase",
            chapterHref = "chap01.xhtml",
            styles = setOf(com.riffle.core.domain.EmphasisStyle.BOLD),
            originFontFamily = TEST_FONT,
        )

        val laterStore = buildStore(dao = dao, deviceId = "device-B", clock = { 9500L }, idGenerator = { "unused" })
        laterStore.updateEmphasisStyles(
            id = "uuid-em",
            styles = setOf(com.riffle.core.domain.EmphasisStyle.BOLD, com.riffle.core.domain.EmphasisStyle.STRIKE),
        )

        val saved = dao.getById("uuid-em")!!
        assertEquals("bold,strike", saved.emphasisStyles)
        assertEquals(9500L, saved.updatedAt)
        assertEquals("device-B", saved.lastModifiedByDeviceId)
    }

    @Test
    fun `observeEmphasis filters out highlights and bookmarks`() = runTest {
        val dao = FakeAnnotationDao()
        var n = 0
        val store = buildStore(dao = dao, idGenerator = { "id-${n++}" })

        store.createHighlight("abs1", "item1", "epubcfi(a)", "h", "c", originFontFamily = TEST_FONT)
        store.createBookmark("abs1", "item1", "epubcfi(b)", "snip", "c",
            spineIndex = 0, progression = 0.0, bookmarkTitle = "", originFontFamily = TEST_FONT)
        store.createEmphasis(
            sourceId = "abs1",
            itemId = "item1",
            cfi = "epubcfi(c)",
            textSnippet = "emph",
            chapterHref = "c",
            styles = setOf(com.riffle.core.domain.EmphasisStyle.ITALIC),
            originFontFamily = TEST_FONT,
        )

        val emphasis = store.observeEmphasis("abs1", "item1").first()
        assertEquals(1, emphasis.size)
        assertEquals(AnnotationEntity.TYPE_EMPHASIS, emphasis[0].type)
        assertEquals(setOf(com.riffle.core.domain.EmphasisStyle.ITALIC), emphasis[0].emphasisStyles)
    }
}
