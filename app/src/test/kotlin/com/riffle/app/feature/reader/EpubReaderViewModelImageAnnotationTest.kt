package com.riffle.app.feature.reader

import com.riffle.core.data.AnnotationStoreImpl
import com.riffle.core.database.AnnotationDao
import com.riffle.core.database.AnnotationEntity
import com.riffle.core.domain.DeviceIdStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Regression coverage for [EpubReaderViewModel.onFigureLongPress]. The ViewModel itself can't be
 * constructed in a JVM test (Readium needs android.net.Uri — see [EpubReaderViewModelTest]'s file
 * header), so this exercises the real [AnnotationStoreImpl] the way `onFigureLongPress` calls it:
 * building the same [AnnotationEntity] shape from a [FigureLongPressPayload] and a current-location
 * snapshot (chapterHref / spineIndex / progression), mirroring the production control flow
 * one-to-one so a regression here maps directly to the ViewModel.
 *
 * Regression assertions (would flip red if the imageHref/imageSvg routing in
 * [AnnotationStoreImpl.createImageAnnotation] were swapped or the type were wrong):
 *  (a) the persisted annotation's `type == TYPE_IMAGE`
 *  (b) raster figure: `imageHref` set, `imageSvg` null
 *  (c) inline SVG figure: `imageSvg` set, `imageHref` null
 */
class EpubReaderViewModelImageAnnotationTest {

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
            flowOf(emptyList())
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
     * Mirrors [EpubReaderViewModel.onFigureLongPress]'s call into
     * [com.riffle.core.domain.AnnotationStore.createImageAnnotation]: caption → textSnippet,
     * href/svg passed straight through, embeddedFigures never set (TYPE_HIGHLIGHT-only field).
     */
    private suspend fun onFigureLongPress(
        payload: FigureLongPressPayload,
        chapterHref: String,
        spineIndex: Int,
        progression: Double,
    ) = store().createImageAnnotation(
        sourceId = "srv-abs",
        itemId = "item-1",
        cfi = "epubcfi(/6/4!/4/2:0)",
        textSnippet = payload.caption,
        chapterHref = chapterHref,
        spineIndex = spineIndex,
        progression = progression,
        imageHref = payload.href,
        imageSvg = payload.svg,
    )

    @Test
    fun `createImageAnnotation persists TYPE_IMAGE with caption and href`() = runTest {
        val payload = FigureLongPressPayload(
            kind = "img", caption = "Fig 1", href = "images/g.png", svg = null, elementId = null,
        )

        val saved = onFigureLongPress(payload, chapterHref = "ch1.xhtml", spineIndex = 0, progression = 0.42)

        assertEquals(AnnotationEntity.TYPE_IMAGE, saved.type)
        assertEquals("Fig 1", saved.textSnippet)
        assertEquals("images/g.png", saved.imageHref)
        assertNull(saved.imageSvg)
        assertEquals("ch1.xhtml", saved.chapterHref)
        assertEquals(0.42, saved.progression, 0.0001)
        assertNull(saved.embeddedFigures)
    }

    @Test
    fun `createImageAnnotation on inline svg stores serialized svg not href`() = runTest {
        val payload = FigureLongPressPayload(
            kind = "svg", caption = "Diagram", href = null, svg = "<svg><rect/></svg>", elementId = null,
        )

        val saved = onFigureLongPress(payload, chapterHref = "ch1.xhtml", spineIndex = 0, progression = 0.5)

        assertEquals(AnnotationEntity.TYPE_IMAGE, saved.type)
        assertNull(saved.imageHref)
        assertEquals("<svg><rect/></svg>", saved.imageSvg)
    }

    @Test
    fun `createImageAnnotation sets textSnippet from payload caption`() = runTest {
        val payload = FigureLongPressPayload(
            kind = "img", caption = "The Great Wave", href = "images/wave.jpg", svg = null, elementId = null,
        )

        val saved = onFigureLongPress(payload, chapterHref = "ch2.xhtml", spineIndex = 1, progression = 0.1)

        assertEquals("The Great Wave", saved.textSnippet)
    }
}
