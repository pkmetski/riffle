package com.riffle.app.feature.reader

import com.riffle.core.data.AnnotationStoreImpl
import com.riffle.core.database.AnnotationDao
import com.riffle.core.database.AnnotationEntity
import com.riffle.core.database.BookHighlightSummary
import com.riffle.core.domain.DeviceIdStore
import com.riffle.core.domain.EmbeddedFigure
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Regression coverage for [EpubReaderViewModel.createHighlight]'s Task 7 extension: resolving
 * figures enclosed by the highlight's CFI range via [FiguresInRangeResolver] before persisting.
 *
 * The ViewModel itself can't be constructed in a JVM test (Readium needs android.net.Uri — see
 * [EpubReaderViewModelTest]'s file header), so this mirrors the production control flow one-to-one:
 * resolve figures via a fake [FiguresInRangeResolver], then call the real [AnnotationStoreImpl]
 * exactly as `createHighlight` does — proving the plumbing end to end, including the
 * empty-list-becomes-null codec in [AnnotationStoreImpl].
 *
 * Regression assertion: a resolver that returns an empty list must leave `embeddedFigures == null`
 * on the persisted entity (NOT a serialized `"[]"`). Reverting to serialize-empty-as-`"[]"` flips
 * `createHighlight leaves embeddedFigures null when no figures in range` red.
 */
class EpubReaderViewModelEmbeddedFiguresTest {

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

        override suspend fun backfillNullOriginFontFamily(
            sourceId: String,
            itemId: String,
            fontFamily: String,
            updatedAt: Long,
            deviceId: String,
        ): Int {
            var changed = 0
            rows.value = rows.value.map {
                if (it.sourceId == sourceId && it.itemId == itemId && !it.deleted && it.originFontFamily == null) {
                    changed++
                    it.copy(originFontFamily = fontFamily, updatedAt = updatedAt, lastModifiedByDeviceId = deviceId)
                } else it
            }
            return changed
        }

        override suspend fun healSentinelOriginFontFamily(
            sourceId: String,
            itemId: String,
            sentinel: String,
            fontFamily: String,
            updatedAt: Long,
            deviceId: String,
        ): Int = 0


        override fun observeBooksWithHighlights(sourceId: String): Flow<List<BookHighlightSummary>> =
            flowOf(emptyList())
        override suspend fun updateEmphasisStyles(id: String, emphasisStyles: String, updatedAt: Long, deviceId: String): Int = 0
    }

    private val deviceIdStore = object : DeviceIdStore {
        override suspend fun getOrCreate(): String = "device-X"
    }

    private fun store() = AnnotationStoreImpl(
        dao = dao,
        deviceIdStore = deviceIdStore,
        clock = { 1_000L },
        idGenerator = { "fixed-id" },
    )

    /**
     * Mirrors [EpubReaderViewModel.createHighlight]'s Task 7 control flow: resolve figures via
     * [resolver] for [cfiRange], then persist through [AnnotationStore.createHighlight] with the
     * resolved list.
     */
    private suspend fun createHighlightWithResolver(
        resolver: FiguresInRangeResolver,
        cfiRange: String,
    ) = store().createHighlight(
        sourceId = "srv-abs",
        itemId = "item-1",
        cfi = cfiRange,
        textSnippet = "some selected text",
        chapterHref = "ch1.xhtml",
        spineIndex = 0,
        progression = 0.2,
        embeddedFigures = resolver.resolve(cfiRange),
        originFontFamily = "Georgia, serif",
    )

    @Test
    fun `createHighlight attaches embedded figures when range crosses a figure`() = runTest {
        val fakeResolver = FiguresInRangeResolver { _ ->
            listOf(EmbeddedFigure(href = "img/g.png", svg = null, caption = "Fig 1", order = 0))
        }

        val saved = createHighlightWithResolver(fakeResolver, "epubcfi(/6/4!/4/2,/1:0,/1:10)")

        assertEquals(AnnotationEntity.TYPE_HIGHLIGHT, saved.type)
        val figs = saved.embeddedFigures
        assertEquals(1, figs?.size)
        assertEquals("img/g.png", figs?.first()?.href)
        assertEquals("Fig 1", figs?.first()?.caption)
    }

    @Test
    fun `createHighlight leaves embeddedFigures null when no figures in range`() = runTest {
        val fakeResolver = FiguresInRangeResolver { _ -> emptyList() }

        val saved = createHighlightWithResolver(fakeResolver, "epubcfi(/6/4!/4/2,/1:0,/1:10)")

        assertEquals(AnnotationEntity.TYPE_HIGHLIGHT, saved.type)
        assertNull(saved.embeddedFigures)
    }
}
