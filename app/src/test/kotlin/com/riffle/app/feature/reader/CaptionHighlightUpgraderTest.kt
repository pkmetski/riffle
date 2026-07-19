package com.riffle.app.feature.reader

import com.riffle.core.data.AnnotationStoreImpl
import com.riffle.core.database.AnnotationDao
import com.riffle.core.database.AnnotationEntity
import com.riffle.core.database.BookHighlightSummary
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
 * Regression coverage for [CaptionHighlightUpgrader]. Exercises the opportunistic legacy-annotation
 * upgrade sweep the reader fires once per (VM lifecycle, book): each `TYPE_IMAGE` annotation is
 * checked against the current chapter DOM; if the figure and its caption both resolve and the
 * caption text matches the stored snippet, the annotation is rewritten in place as a `TYPE_HIGHLIGHT`
 * covering the caption with the figure carried as an `embeddedFigure`.
 */
class CaptionHighlightUpgraderTest {

    private val rows = MutableStateFlow<List<AnnotationEntity>>(emptyList())
    private val dao = InMemoryDao(rows)

    private val deviceIdStore = object : DeviceIdStore {
        override suspend fun getOrCreate(): String = "device-X"
    }

    private fun store(): AnnotationStoreImpl {
        var counter = 0
        return AnnotationStoreImpl(
            dao = dao,
            deviceIdStore = deviceIdStore,
            clock = { 5_000L },
            idGenerator = { "generated-${counter++}" },
        )
    }

    private fun legacyImageRow(
        id: String = "ann-1",
        textSnippet: String = "Figure 2. The observed distribution",
        imageHref: String = "OEBPS/images/graph.png",
        imageSvg: String? = null,
        imageBytes: String? = "data:image/jpeg;base64,ZZZZ",
        spineIndex: Int = 2,
    ) = AnnotationEntity(
        id = id,
        sourceId = "srv",
        itemId = "book-1",
        type = AnnotationEntity.TYPE_IMAGE,
        cfi = "epubcfi(/6/4!/4/2:0)",
        color = AnnotationEntity.COLOR_YELLOW,
        note = null,
        textSnippet = textSnippet,
        textBefore = "",
        textAfter = "",
        chapterHref = "OEBPS/ch2.xhtml",
        spineIndex = spineIndex,
        progression = 0.5,
        bookmarkTitle = "",
        createdAt = 1_000L,
        updatedAt = 2_000L,
        originDeviceId = "device-orig",
        lastModifiedByDeviceId = "device-orig",
        deleted = false,
        embeddedFigures = null,
        imageHref = imageHref,
        imageSvg = imageSvg,
        imageBytes = imageBytes,
    )

    @Test
    fun `upgrades legacy TYPE_IMAGE when figcaption resolves and matches snippet`() = runTest {
        val legacy = legacyImageRow(textSnippet = "Figure 2. The observed distribution")
        dao.upsert(legacy)
        val html = """
            <html><body>
              <p>Some preceding text before the graph.</p>
              <figure>
                <img src="images/graph.png"/>
                <figcaption>Figure 2. The observed distribution</figcaption>
              </figure>
              <p>Following prose.</p>
            </body></html>
        """.trimIndent()
        val upgrader = CaptionHighlightUpgrader(store())

        val upgraded = upgrader.upgradeLegacyImageAnnotations(
            annotations = dao.rows.value.map { it.toDomain() },
            readChapterHtml = { _ -> html },
        )

        assertEquals(1, upgraded)
        val row = dao.rows.value.single()
        assertEquals(AnnotationEntity.TYPE_HIGHLIGHT, row.type)
        assertEquals("Figure 2. The observed distribution", row.textSnippet)
        assertNull("TYPE_HIGHLIGHT rows must not carry imageHref", row.imageHref)
        assertNull("TYPE_HIGHLIGHT rows must not carry imageBytes", row.imageBytes)
        assertTrue(
            "embeddedFigures JSON must reference the figure href",
            (row.embeddedFigures ?: "").contains("graph.png"),
        )
        // Id preserved so cross-device sync sees an update, not a delete/create.
        assertEquals("ann-1", row.id)
    }

    @Test
    fun `upgrades via text-prefix fallback when no figcaption present`() = runTest {
        // LaTeX/Kotobee/Vellum EPUBs typically wrap the caption as a plain <p> whose text starts
        // with "Figure N…". The upgrader mirrors the JS resolveCaption fallback.
        val legacy = legacyImageRow(textSnippet = "Figure 2.1 The observed distribution across cohorts")
        dao.upsert(legacy)
        val html = """
            <html><body>
              <div>
                <img src="images/graph.png"/>
                <p>Figure 2.1 The observed distribution across cohorts</p>
              </div>
            </body></html>
        """.trimIndent()
        val upgrader = CaptionHighlightUpgrader(store())

        val upgraded = upgrader.upgradeLegacyImageAnnotations(
            annotations = dao.rows.value.map { it.toDomain() },
            readChapterHtml = { _ -> html },
        )

        assertEquals(1, upgraded)
        assertEquals(AnnotationEntity.TYPE_HIGHLIGHT, dao.rows.value.single().type)
    }

    @Test
    fun `skips upgrade when DOM caption does not match stored snippet`() = runTest {
        // Publisher republished with a different caption. Refuse to upgrade — annotation stays as
        // legacy TYPE_IMAGE, its CSS caption tint keeps rendering unchanged.
        val legacy = legacyImageRow(textSnippet = "Figure 2. Original caption")
        dao.upsert(legacy)
        val html = """
            <html><body>
              <figure>
                <img src="images/graph.png"/>
                <figcaption>Figure 2. Renamed caption entirely</figcaption>
              </figure>
            </body></html>
        """.trimIndent()
        val upgrader = CaptionHighlightUpgrader(store())

        val upgraded = upgrader.upgradeLegacyImageAnnotations(
            annotations = dao.rows.value.map { it.toDomain() },
            readChapterHtml = { _ -> html },
        )

        assertEquals(0, upgraded)
        assertEquals(AnnotationEntity.TYPE_IMAGE, dao.rows.value.single().type)
    }

    @Test
    fun `skips upgrade when figure not in DOM`() = runTest {
        // Chapter reorganised, figure href stale — nothing to hang the highlight on.
        val legacy = legacyImageRow()
        dao.upsert(legacy)
        val html = """<html><body><p>No figures here.</p></body></html>"""
        val upgrader = CaptionHighlightUpgrader(store())

        val upgraded = upgrader.upgradeLegacyImageAnnotations(
            annotations = dao.rows.value.map { it.toDomain() },
            readChapterHtml = { _ -> html },
        )

        assertEquals(0, upgraded)
    }

    @Test
    fun `skips upgrade when figure has no caption element`() = runTest {
        // Bare <img> with alt-text only: no visible caption to anchor to. Annotation must stay
        // TYPE_IMAGE (its textSnippet still holds the alt for the annotations panel).
        val legacy = legacyImageRow(textSnippet = "Photo of author")
        dao.upsert(legacy)
        val html = """
            <html><body>
              <p>Introduction paragraph.</p>
              <img src="images/graph.png" alt="Photo of author"/>
              <p>Continuing prose.</p>
            </body></html>
        """.trimIndent()
        val upgrader = CaptionHighlightUpgrader(store())

        val upgraded = upgrader.upgradeLegacyImageAnnotations(
            annotations = dao.rows.value.map { it.toDomain() },
            readChapterHtml = { _ -> html },
        )

        assertEquals(0, upgraded)
        assertEquals(AnnotationEntity.TYPE_IMAGE, dao.rows.value.single().type)
    }

    @Test
    fun `preserves provenance and bumps updatedAt on upgrade`() = runTest {
        val legacy = legacyImageRow()
        dao.upsert(legacy)
        val html = """
            <html><body>
              <figure>
                <img src="images/graph.png"/>
                <figcaption>${legacy.textSnippet}</figcaption>
              </figure>
            </body></html>
        """.trimIndent()
        val upgrader = CaptionHighlightUpgrader(store())

        upgrader.upgradeLegacyImageAnnotations(
            annotations = dao.rows.value.map { it.toDomain() },
            readChapterHtml = { _ -> html },
        )

        val row = dao.rows.value.single()
        // originDeviceId is stable — the annotation didn't change origin, just shape.
        assertEquals("device-orig", row.originDeviceId)
        assertEquals("device-X", row.lastModifiedByDeviceId)
        // Clock stamps updatedAt = now() so sync sees the row as dirty and pushes the new shape.
        assertEquals(5_000L, row.updatedAt)
    }

    @Test
    fun `sweep merges duplicate caption HIGHLIGHTs into one`() = runTest {
        // Regression: the initial 2026-07-14 caption-annotation change could stack a second
        // HIGHLIGHT next to a first when a re-long-press of the same figure hit before the first
        // annotation's embeddedFigures was populated (the "1.5s duplicate" the user reproduced on
        // AVD 5554). The cleanup phase must merge duplicate HIGHLIGHTs at the same caption text
        // into one canonical annotation carrying every figure and tombstone the extras.
        val figureA = com.riffle.core.models.EmbeddedFigure(
            href = "images/g.png", svg = null, caption = "Figure 20.2: The original",
            order = 0, imageBytes = "data:image/jpeg;base64,YYY", charOffset = 0L,
        )
        val chapter = "OEBPS/ch20.xhtml"
        // Seed a HIGHLIGHT with the figure via the store's own createHighlight path — same
        // production write path, so the JSON shape is real.
        val storeInstance = store()
        val withFig = storeInstance.createHighlight(
            sourceId = "srv", itemId = "book-1",
            cfi = "epubcfi(range-a)",
            textSnippet = "Figure 20.2: The original",
            chapterHref = chapter,
            embeddedFigures = listOf(figureA),
            originFontFamily = "serif",
        )
        val empty = storeInstance.createHighlight(
            sourceId = "srv", itemId = "book-1",
            cfi = "epubcfi(range-b)",
            textSnippet = "Figure 20.2: The original",
            chapterHref = chapter,
            embeddedFigures = null,
            originFontFamily = "serif",
        )
        // Set the with-fig row's updatedAt higher so it becomes canonical (also has figures →
        // tiebreaker wins). The empty row is older.
        dao.upsert(dao.rows.value.single { it.id == withFig.id }.copy(updatedAt = 200L))
        dao.upsert(dao.rows.value.single { it.id == empty.id }.copy(updatedAt = 100L))

        val result = CaptionHighlightUpgrader(storeInstance).sweep(
            annotations = dao.rows.value.map { it.toDomain() },
            readChapterHtml = { _ -> "" },
        )

        assertEquals(1, result.merged)
        val live = dao.rows.value.filterNot { it.deleted }
        assertEquals("one canonical row survives the merge", 1, live.size)
        val canonical = live.single()
        assertEquals(withFig.id, canonical.id)
        val tomb = dao.rows.value.single { it.id == empty.id }
        assertTrue("empty duplicate must be tombstoned", tomb.deleted)
    }

    @Test
    fun `sweep merges legacy TYPE_IMAGE into pre-existing caption HIGHLIGHT`() = runTest {
        // When a legacy TYPE_IMAGE of a figure coexists with a pre-existing text-selection
        // HIGHLIGHT of the same caption (e.g. user text-highlighted the caption manually before
        // long-pressing the figure), the upgrade must fold the figure into that HIGHLIGHT and
        // tombstone the TYPE_IMAGE — not upgrade it into a duplicate HIGHLIGHT alongside.
        val chapter = "OEBPS/ch20.xhtml"
        val storeInstance = store()
        val existingCaptionHighlight = storeInstance.createHighlight(
            sourceId = "srv", itemId = "book-1",
            cfi = "epubcfi(existing)",
            textSnippet = "Figure 20.1: A Buffer object uses",
            chapterHref = chapter,
            embeddedFigures = null,
            originFontFamily = "serif",
        )
        val legacyImage = legacyImageRow(
            id = "legacy-img",
            textSnippet = "Figure 20.1: A Buffer object uses",
            imageHref = "images/graph.png",
        ).copy(chapterHref = chapter)
        dao.upsert(legacyImage)
        val html = """
            <html><body>
              <figure>
                <img src="images/graph.png"/>
                <figcaption>Figure 20.1: A Buffer object uses</figcaption>
              </figure>
            </body></html>
        """.trimIndent()

        val result = CaptionHighlightUpgrader(storeInstance).sweep(
            annotations = dao.rows.value.map { it.toDomain() },
            readChapterHtml = { _ -> html },
        )

        assertEquals(1, result.upgraded)
        val live = dao.rows.value.filterNot { it.deleted }
        assertEquals("only the user's highlight survives", 1, live.size)
        assertEquals(existingCaptionHighlight.id, live.single().id)
        assertTrue(
            "user-highlight now carries the figure",
            (live.single().embeddedFigures ?: "").contains("graph.png"),
        )
        val tomb = dao.rows.value.single { it.id == "legacy-img" }
        assertTrue("legacy TYPE_IMAGE must be tombstoned", tomb.deleted)
    }

    @Test
    fun `mergeFiguresIntoHighlight unions figures deduped by filename`() = runTest {
        // The store-level dedup: adding the same figure (same filename suffix) twice keeps a
        // single entry. Preventing a re-long-press from bloating embeddedFigures with duplicates
        // — the "5 copies of the same image" post-fix regression that would otherwise appear.
        val figA = com.riffle.core.models.EmbeddedFigure(
            href = "images/g.png", svg = null, caption = "cap", order = 0,
        )
        val figAAgain = com.riffle.core.models.EmbeddedFigure(
            href = "https://cdn/images/g.png?v=2", svg = null, caption = "cap", order = 0,
        )
        val figB = com.riffle.core.models.EmbeddedFigure(
            href = "images/h.png", svg = null, caption = "cap", order = 0,
        )
        val storeInstance = store()
        val seed = storeInstance.createHighlight(
            sourceId = "srv", itemId = "book-1",
            cfi = "epubcfi(x)",
            textSnippet = "cap",
            chapterHref = "ch",
            embeddedFigures = listOf(figA),
            originFontFamily = "serif",
        )

        storeInstance.mergeFiguresIntoHighlight(seed.id, listOf(figAAgain, figB))

        val json = dao.rows.value.single().embeddedFigures ?: ""
        assertEquals("only two figures: original + h.png", 2, json.split("\"href\"").size - 1)
        assertTrue("h.png added", json.contains("h.png"))
    }

    @Test
    fun `skips already-upgraded TYPE_HIGHLIGHT rows`() = runTest {
        val already = legacyImageRow().copy(
            type = AnnotationEntity.TYPE_HIGHLIGHT,
            imageHref = null,
        )
        dao.upsert(already)
        val html = "<html><body></body></html>"
        val upgrader = CaptionHighlightUpgrader(store())

        val upgraded = upgrader.upgradeLegacyImageAnnotations(
            annotations = dao.rows.value.map { it.toDomain() },
            readChapterHtml = { _ -> html },
        )

        assertEquals(0, upgraded)
    }
}

/**
 * Reusable in-memory [AnnotationDao] fake for the upgrader tests. Mirrors the fake in
 * [EpubReaderViewModelImageAnnotationTest] but lives in its own class so the two suites don't
 * fight over shared MutableState.
 */
private class InMemoryDao(val rows: MutableStateFlow<List<AnnotationEntity>>) : AnnotationDao {

    override fun observeForItem(sourceId: String, itemId: String): Flow<List<AnnotationEntity>> =
        rows.map { all -> all.filter { it.sourceId == sourceId && it.itemId == itemId && !it.deleted } }

    override suspend fun getForItem(sourceId: String, itemId: String): List<AnnotationEntity> =
        rows.value.filter { it.sourceId == sourceId && it.itemId == itemId && !it.deleted }

    override suspend fun getAllForItemIncludingDeleted(sourceId: String, itemId: String) =
        rows.value.filter { it.sourceId == sourceId && it.itemId == itemId }

    override suspend fun getById(id: String) = rows.value.firstOrNull { it.id == id }

    override suspend fun getByItemAndCfi(sourceId: String, itemId: String, cfi: String) =
        rows.value.firstOrNull { it.sourceId == sourceId && it.itemId == itemId && it.cfi == cfi && !it.deleted }

    override suspend fun findImageForFigure(
        sourceId: String,
        itemId: String,
        chapterHref: String,
        imageHref: String?,
        imageSvg: String?,
    ) = rows.value.firstOrNull {
        it.sourceId == sourceId && it.itemId == itemId && it.chapterHref == chapterHref &&
            it.type == AnnotationEntity.TYPE_IMAGE && !it.deleted &&
            (imageHref == null || it.imageHref == imageHref) &&
            (imageSvg == null || it.imageSvg == imageSvg)
    }

    override suspend fun upsert(entity: AnnotationEntity) {
        rows.value = rows.value.filterNot { it.id == entity.id } + entity
    }

    override suspend fun upsertAll(annotations: List<AnnotationEntity>) {
        val ids = annotations.map { it.id }.toSet()
        rows.value = rows.value.filterNot { it.id in ids } + annotations
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

    override fun observeAnnotationsByPosition(sourceId: String, itemId: String) =
        rows.map { all ->
            all.filter { it.sourceId == sourceId && it.itemId == itemId && !it.deleted }
                .sortedWith(compareBy({ it.spineIndex }, { it.progression }))
        }

    override suspend fun renameBookmark(id: String, title: String, updatedAt: Long, deviceId: String) {
        rows.value = rows.value.map {
            if (it.id == id) it.copy(bookmarkTitle = title, updatedAt = updatedAt, lastModifiedByDeviceId = deviceId) else it
        }
    }

    override fun observeForSource(sourceId: String) =
        rows.map { all -> all.filter { it.sourceId == sourceId && !it.deleted } }

    override fun observePendingCountForBook(sourceId: String, itemId: String) =
        rows.map { all -> all.count { it.sourceId == sourceId && it.itemId == itemId && it.updatedAt > it.lastSyncedAt } }

    override fun observePendingBookCountAcrossAll() =
        rows.map { all -> all.filter { it.updatedAt > it.lastSyncedAt }.distinctBy { it.sourceId to it.itemId }.size }

    override suspend fun dirtySourceItems() =
        rows.value.filter { it.updatedAt > it.lastSyncedAt }
            .map { AnnotationDao.DirtySourceItem(it.sourceId, it.itemId) }
            .distinct()

    override suspend fun markSynced(ids: List<String>, syncedAt: Long) {
        rows.value = rows.value.map { if (it.id in ids) it.copy(lastSyncedAt = syncedAt) else it }
    }

    override suspend fun purgeAgedTombstones(sourceId: String, itemId: String, cutoff: Long) = 0

    override suspend fun backfillNullOriginFontFamily(
        sourceId: String,
        itemId: String,
        fontFamily: String,
        updatedAt: Long,
        deviceId: String,
    ) = 0

    override suspend fun healSentinelOriginFontFamily(
        sourceId: String,
        itemId: String,
        sentinel: String,
        fontFamily: String,
        updatedAt: Long,
        deviceId: String,
    ) = 0

    override fun observeBooksWithHighlights(sourceId: String) = flowOf(emptyList<BookHighlightSummary>())
    override suspend fun updateEmphasisStyles(id: String, emphasisStyles: String, updatedAt: Long, deviceId: String): Int = 0
}

/** Reuses the internal mapper the store uses so this test's fixtures speak entity, not domain. */
private fun AnnotationEntity.toDomain() = com.riffle.core.models.Annotation(
    id = id,
    sourceId = sourceId,
    itemId = itemId,
    type = type,
    cfi = cfi,
    color = color,
    note = note,
    textSnippet = textSnippet,
    textBefore = textBefore,
    textAfter = textAfter,
    chapterHref = chapterHref,
    spineIndex = spineIndex,
    progression = progression,
    bookmarkTitle = bookmarkTitle,
    createdAt = createdAt,
    updatedAt = updatedAt,
    embeddedFigures = null,
    imageHref = imageHref,
    imageSvg = imageSvg,
    imageBytes = imageBytes,
    originFontFamily = originFontFamily,
)
