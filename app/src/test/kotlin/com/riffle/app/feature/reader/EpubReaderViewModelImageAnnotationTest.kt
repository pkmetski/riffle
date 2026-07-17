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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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


        override fun observeBooksWithHighlights(sourceId: String): Flow<List<com.riffle.core.database.BookHighlightSummary>> =
            flowOf(emptyList())
        override suspend fun updateEmphasisStyles(id: String, emphasisStyles: String, updatedAt: Long, deviceId: String) = Unit
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

    /** Unlike [store], generates a unique id per created annotation — needed by the Task 11 tests
     *  below that create more than one annotation per test and need distinct ids to assert on. */
    private fun storeWithUniqueIds(): AnnotationStoreImpl {
        var counter = 0
        return AnnotationStoreImpl(
            dao = dao,
            deviceIdStore = deviceIdStore,
            clock = { 1_000L },
            idGenerator = { "id-${counter++}" },
        )
    }

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
        imageBytes = payload.imageBytes,
    )

    /**
     * Mirrors [EpubReaderViewModel.onFigureLongPress]'s Task-11 dispatch: look up an existing live
     * `TYPE_IMAGE` annotation for this exact figure in this chapter via
     * [com.riffle.core.domain.AnnotationStore.findImageAnnotationForFigure] first; only create a new
     * one when the lookup comes back null. Returns the id of the annotation that would have opened
     * the highlight-actions popup for editing, or null when a new annotation was created instead —
     * this mirrors [EpubReaderViewModel.onFigureLongPress]'s existing-vs-create dispatch without
     * needing the full (Readium-backed, non-JVM-constructible) ViewModel.
     */
    private suspend fun dispatchFigureLongPress(
        store: AnnotationStoreImpl,
        payload: FigureLongPressPayload,
        chapterHref: String,
        spineIndex: Int,
        progression: Double,
    ): String? {
        val existing = store.findImageAnnotationForFigure(
            sourceId = "srv-abs",
            itemId = "item-1",
            chapterHref = chapterHref,
            imageHref = payload.href,
            imageSvg = payload.svg,
        )
        if (existing != null) return existing.id
        store.createImageAnnotation(
            sourceId = "srv-abs",
            itemId = "item-1",
            cfi = "epubcfi(/6/4!/4/2:0)",
            textSnippet = payload.caption,
            chapterHref = chapterHref,
            spineIndex = spineIndex,
            progression = progression,
            imageHref = payload.href,
            imageSvg = payload.svg,
            imageBytes = payload.imageBytes,
        )
        return null
    }

    /**
     * Mirrors [EpubReaderViewModel.onFigureLongPress]'s caption-highlight branch (2026-07-14):
     * when the JS payload carried a `captionRange`, resolve the caption's char range Kotlin-side
     * and persist as `TYPE_HIGHLIGHT` covering the caption with the figure carried as an
     * `embeddedFigure`. Fully mirrors the production call site — a schema/type regression on
     * either the store method or the VM branch flips this red.
     */
    private suspend fun onFigureLongPressWithCaptionRange(
        store: AnnotationStoreImpl,
        payload: FigureLongPressPayload,
        html: String,
        chapterHref: String,
        spineIndex: Int,
        progression: Double,
    ): com.riffle.core.domain.Annotation? {
        val captionRange = payload.captionRange ?: return null
        val startChar = locateSnippetInBody(html, captionRange.text, captionRange.textBefore) ?: return null
        val cfi = buildHighlightCfiRange(
            spineStep = (spineIndex + 1) * 2,
            html = html,
            startChar = startChar,
            endChar = (startChar + captionRange.text.length - 1L).coerceAtLeast(startChar),
        ) ?: return null
        val figure = com.riffle.core.domain.EmbeddedFigure(
            href = payload.href,
            svg = payload.svg,
            caption = captionRange.text,
            order = 0,
            imageBytes = payload.imageBytes,
            charOffset = 0L,
        )
        return store.createHighlight(
            sourceId = "srv-abs",
            itemId = "item-1",
            cfi = cfi,
            textSnippet = captionRange.text,
            chapterHref = chapterHref,
            textBefore = captionRange.textBefore,
            textAfter = captionRange.textAfter,
            spineIndex = spineIndex,
            progression = progression,
            embeddedFigures = listOf(figure),
            originFontFamily = "serif",
        )
    }

    @Test
    fun `onFigureLongPress with captionRange persists TYPE_HIGHLIGHT with embedded figure`() = runTest {
        val payload = FigureLongPressPayload(
            kind = "img",
            caption = "Figure 2. The observed distribution",
            href = "images/g.png",
            svg = null,
            elementId = null,
            imageBytes = "data:image/jpeg;base64,ZZZZ",
            captionRange = CaptionRange(
                text = "Figure 2. The observed distribution",
                textBefore = "the graph",
                textAfter = "Following prose",
            ),
        )
        val html = """
            <html><body>
              <p>Prose before the graph</p>
              <figure>
                <img src="images/g.png"/>
                <figcaption>Figure 2. The observed distribution</figcaption>
              </figure>
              <p>Following prose here</p>
            </body></html>
        """.trimIndent()

        val saved = onFigureLongPressWithCaptionRange(
            store = storeWithUniqueIds(),
            payload = payload,
            html = html,
            chapterHref = "ch1.xhtml",
            spineIndex = 0,
            progression = 0.42,
        )

        assertNotNull(saved)
        // Regression: this branch produces TYPE_HIGHLIGHT, not TYPE_IMAGE. A revert would show the
        // caption's text un-highlighted and untappable — the exact bug the 2026-07-14 change fixed.
        assertEquals(AnnotationEntity.TYPE_HIGHLIGHT, saved!!.type)
        assertEquals("Figure 2. The observed distribution", saved.textSnippet)
        assertNull("TYPE_HIGHLIGHT rows must not carry imageHref", saved.imageHref)
        val figures = saved.embeddedFigures.orEmpty()
        assertEquals(1, figures.size)
        assertEquals("images/g.png", figures.single().href)
        assertEquals("data:image/jpeg;base64,ZZZZ", figures.single().imageBytes)
    }

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

    /**
     * Regression coverage for Task 11's edit-vs-create dispatch. Mirrors
     * [EpubReaderViewModel.onFigureLongPress]'s new lookup-first flow via [dispatchFigureLongPress].
     * Reverting the lookup (going back to an unconditional [AnnotationStoreImpl.createImageAnnotation]
     * call) would flip [rows]' size from 1 back to 2 in the first test below.
     */
    @Test
    fun `two long-presses on the same figure create only one annotation`() = runTest {
        val store = storeWithUniqueIds()
        val payload = FigureLongPressPayload(
            kind = "img", caption = "Fig 1", href = "images/g.png", svg = null, elementId = null,
        )

        val first = dispatchFigureLongPress(store, payload, chapterHref = "ch1.xhtml", spineIndex = 0, progression = 0.5)
        val second = dispatchFigureLongPress(store, payload, chapterHref = "ch1.xhtml", spineIndex = 0, progression = 0.5)

        assertNull(first)
        assertEquals(1, rows.value.size)
        assertEquals(rows.value.single().id, second)
    }

    @Test
    fun `long-presses on different figures create separate annotations`() = runTest {
        val store = storeWithUniqueIds()
        val figureA = FigureLongPressPayload(
            kind = "img", caption = "Fig 1", href = "images/a.png", svg = null, elementId = null,
        )
        val figureB = FigureLongPressPayload(
            kind = "img", caption = "Fig 2", href = "images/b.png", svg = null, elementId = null,
        )

        dispatchFigureLongPress(store, figureA, chapterHref = "ch1.xhtml", spineIndex = 0, progression = 0.5)
        dispatchFigureLongPress(store, figureB, chapterHref = "ch1.xhtml", spineIndex = 0, progression = 0.6)

        assertEquals(2, rows.value.size)
    }

    @Test
    fun `long-press on soft-deleted matching annotation creates a new one`() = runTest {
        val store = storeWithUniqueIds()
        val payload = FigureLongPressPayload(
            kind = "img", caption = "Fig 1", href = "images/g.png", svg = null, elementId = null,
        )

        val first = dispatchFigureLongPress(store, payload, chapterHref = "ch1.xhtml", spineIndex = 0, progression = 0.5)
        assertNull(first)
        val originalId = rows.value.single().id
        store.delete(originalId)

        val second = dispatchFigureLongPress(store, payload, chapterHref = "ch1.xhtml", spineIndex = 0, progression = 0.5)

        assertNull(second)
        val live = rows.value.filterNot { it.deleted }
        assertEquals(1, live.size)
        assertTrue(live.single().id != originalId)
    }
}
