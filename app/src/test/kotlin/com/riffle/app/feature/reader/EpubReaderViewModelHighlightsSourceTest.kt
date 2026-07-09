package com.riffle.app.feature.reader

import android.net.FakeUri
import com.riffle.app.feature.reader.highlights.HighlightsPublicationFactory
import com.riffle.app.feature.reader.highlights.ReaderSource
import com.riffle.core.database.AnnotationEntity
import com.riffle.core.domain.Annotation
import com.riffle.core.domain.TocEntry
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.readium.r2.shared.util.RelativeUrl
import org.readium.r2.shared.util.Url

/**
 * [EpubReaderViewModel] cannot be constructed directly in a JVM unit test — it's an
 * [androidx.lifecycle.AndroidViewModel] with Readium dependencies that route through
 * `android.net.Uri`, unmocked under the stock (non-Robolectric) Android test stub jar this module
 * uses (see [EpubReaderViewModelTest]'s file-level comment for the same constraint). Per Task 7's
 * brief, the grouping/build logic that `loadHighlightsPublication` delegates to has been extracted
 * into the top-level `internal fun buildChapterElisions` in EpubReaderViewModel.kt, so this test
 * exercises that function directly, then feeds its output through the *real*
 * [HighlightsPublicationFactory] (same JVM Url-fixture seam as [com.riffle.app.feature.reader.highlights.HighlightsPublicationFactoryTest]) to
 * pin the behaviour Task 7 actually demands: Highlights-mode loads a synthesised Publication whose
 * readingOrder contains one link per chapter with highlights — not the ABS EPUB's full spine.
 */
class EpubReaderViewModelHighlightsSourceTest {

    @Suppress("UNCHECKED_CAST")
    private fun testUrlFactory(href: String): Url {
        val unsafe = Class.forName("sun.misc.Unsafe")
            .getDeclaredField("theUnsafe")
            .also { it.isAccessible = true }
            .get(null) as sun.misc.Unsafe
        val url = unsafe.allocateInstance(RelativeUrl::class.java) as RelativeUrl
        RelativeUrl::class.java.getDeclaredField("uri")
            .also { it.isAccessible = true }
            .set(url, FakeUri(href))
        return url
    }

    private fun highlight(
        id: String,
        chapterHref: String,
        spineIndex: Int,
        progression: Double = 0.0,
        createdAt: Long = 0L,
        deleted: Boolean = false,
        type: String = AnnotationEntity.TYPE_HIGHLIGHT,
    ): AnnotationEntity = AnnotationEntity(
        id = id,
        sourceId = "S1",
        itemId = "B1",
        type = type,
        cfi = "epubcfi(/6/${(spineIndex + 1) * 2}!/dummy)",
        textSnippet = "snippet-$id",
        chapterHref = chapterHref,
        spineIndex = spineIndex,
        progression = progression,
        createdAt = createdAt,
        updatedAt = createdAt,
        originDeviceId = "test",
        lastModifiedByDeviceId = "test",
        deleted = deleted,
    )

    // The regression this pins: three highlights across two chapters must produce a Publication
    // with readingOrder.size == 2 (one per chapter WITH highlights), not the ABS EPUB's full spine
    // (which a fake EpubRepository that throws on open proves is never consulted in this path).
    @Test
    fun `Highlights mode publication reading order has one link per chapter with highlights`() {
        val rows = listOf(
            highlight("h1", "chA.xhtml", spineIndex = 0, progression = 0.5, createdAt = 3L),
            highlight("h2", "chA.xhtml", spineIndex = 0, progression = 0.1, createdAt = 1L),
            highlight("h3", "chB.xhtml", spineIndex = 2, progression = 0.2, createdAt = 2L),
            // Noise the grouping must exclude: a bookmark and a soft-deleted highlight.
            highlight("b1", "chA.xhtml", spineIndex = 0, type = AnnotationEntity.TYPE_BOOKMARK),
            highlight("h4", "chC.xhtml", spineIndex = 5, deleted = true),
        )

        val chapters = buildChapterElisions(rows)
        val pub = HighlightsPublicationFactory().build(
            sourceId = "S1",
            itemId = "B1",
            bookTitle = null,
            chapters = chapters,
            urlFactory = ::testUrlFactory,
        )

        assertEquals(2, pub.readingOrder.size)
        assertEquals(listOf("chA", "chB"), pub.tableOfContents.map { it.title })
    }

    // Within a chapter, highlights must render in reading-position order (progression), not
    // insertion/createdAt order — chA's h2 (progression 0.1) must precede h1 (progression 0.5).
    @Test
    fun `highlights within a chapter are ordered by progression not createdAt`() {
        val rows = listOf(
            highlight("h1", "chA.xhtml", spineIndex = 0, progression = 0.5, createdAt = 1L),
            highlight("h2", "chA.xhtml", spineIndex = 0, progression = 0.1, createdAt = 2L),
        )

        val chapters = buildChapterElisions(rows)

        assertEquals(1, chapters.size)
        assertEquals(listOf("h2", "h1"), chapters.single().highlights.map { it.id })
    }

    // Figure annotations must reach the elided reader too — TYPE_IMAGE rows count as chapter content,
    // not noise like bookmarks. Reverting the TYPE_IMAGE branch in the filter drops these rows and
    // this assertion flips red.
    @Test
    fun `TYPE_IMAGE annotations are included alongside TYPE_HIGHLIGHT in chapter elisions`() {
        val rows = listOf(
            highlight("h1", "chA.xhtml", spineIndex = 0, progression = 0.5, type = AnnotationEntity.TYPE_HIGHLIGHT),
            highlight("i1", "chA.xhtml", spineIndex = 0, progression = 0.2, type = AnnotationEntity.TYPE_IMAGE),
            highlight("b1", "chA.xhtml", spineIndex = 0, type = AnnotationEntity.TYPE_BOOKMARK),
        )

        val chapters = buildChapterElisions(rows)

        assertEquals(1, chapters.size)
        assertEquals(listOf("i1", "h1"), chapters.single().highlights.map { it.id })
    }

    @Test
    fun `deriveChapterTitle strips directory and extension`() {
        assertEquals("ch03", deriveChapterTitle("OEBPS/ch03.xhtml"))
        assertEquals("Chapter", deriveChapterTitle(""))
    }

    // ---- Fix B (ADR 0041 follow-up): chapter titles resolved from the cached TOC -----------

    // The regression this pins: without TOC resolution, Highlights-mode chapter headings show the
    // raw href basename ("part0007") instead of the book's real chapter title.
    @Test
    fun `resolveChapterTitle prefers TOC entry title over href basename`() {
        val toc = listOf(TocEntry(title = "The Nature of Complexity", href = "OEBPS/part0007.xhtml"))
        assertEquals("The Nature of Complexity", resolveChapterTitle("OEBPS/part0007.xhtml", toc))
    }

    @Test
    fun `resolveChapterTitle strips fragment when matching`() {
        val toc = listOf(TocEntry(title = "Modules", href = "OEBPS/part0008.xhtml#modules"))
        assertEquals("Modules", resolveChapterTitle("OEBPS/part0008.xhtml", toc))
    }

    @Test
    fun `resolveChapterTitle returns null when no TOC entry matches`() {
        assertNull(resolveChapterTitle("OEBPS/part0009.xhtml", emptyList()))
    }

    @Test
    fun `resolveChapterTitle matches nested TOC entries`() {
        val toc = listOf(
            TocEntry(
                title = "Part One",
                href = "OEBPS/part0001.xhtml",
                children = listOf(TocEntry(title = "Nested Chapter", href = "OEBPS/part0002.xhtml")),
            ),
        )
        assertEquals("Nested Chapter", resolveChapterTitle("OEBPS/part0002.xhtml", toc))
    }

    // ---- Task 10 (ADR 0041): per-device resume position -----------------------------------

    @Test
    fun `highlightsResumeChapterHref resolves the synthesised href for the chapter containing the highlight`() {
        val rows = listOf(
            highlight("h1", "chA.xhtml", spineIndex = 0),
            highlight("h2", "chB.xhtml", spineIndex = 1),
        )
        val chapters = buildChapterElisions(rows)

        // chB is chapters[1] -> synthesised href index 1, matching HighlightsPublicationFactory's
        // "highlights/ch$index.xhtml" naming (see its build()).
        assertEquals("highlights/ch1.xhtml", highlightsResumeChapterHref(chapters, "h2", readingOrderSize = 2))
    }

    @Test
    fun `highlightsResumeChapterHref returns null when the highlight no longer exists`() {
        val chapters = buildChapterElisions(listOf(highlight("h1", "chA.xhtml", spineIndex = 0)))
        assertEquals(null, highlightsResumeChapterHref(chapters, "deleted-id", readingOrderSize = 1))
    }

    @Test
    fun `highlightsResumeChapterHref returns null when the resolved index is out of range`() {
        val chapters = buildChapterElisions(listOf(highlight("h1", "chA.xhtml", spineIndex = 0)))
        // readingOrderSize disagrees with the chapters list (defensive guard) -> no href.
        assertEquals(null, highlightsResumeChapterHref(chapters, "h1", readingOrderSize = 0))
    }

    @Test
    fun `highlightsResumeAnnotationIdForHref is the inverse of highlightsResumeChapterHref`() {
        val rows = listOf(
            highlight("h1", "chA.xhtml", spineIndex = 0, progression = 0.1, createdAt = 1L),
            highlight("h2", "chA.xhtml", spineIndex = 0, progression = 0.5, createdAt = 2L),
            highlight("h3", "chB.xhtml", spineIndex = 1),
        )
        val chapters = buildChapterElisions(rows)

        // chB (index 1) -> its first (only) highlight.
        assertEquals("h3", highlightsResumeAnnotationIdForHref(chapters, "highlights/ch1.xhtml"))
        // chA (index 0) -> its first-by-progression highlight, h1, not first-by-createdAt.
        assertEquals("h1", highlightsResumeAnnotationIdForHref(chapters, "highlights/ch0.xhtml"))
    }

    @Test
    fun `highlightsResumeAnnotationIdForHref returns null for an unrecognised href`() {
        val chapters = buildChapterElisions(listOf(highlight("h1", "chA.xhtml", spineIndex = 0)))
        assertEquals(null, highlightsResumeAnnotationIdForHref(chapters, "not-a-highlights-href.xhtml"))
        assertEquals(null, highlightsResumeAnnotationIdForHref(chapters, "highlights/ch7.xhtml"))
    }

    // ---- Delete-last-annotation crash fix ---------------------------------------------------

    // Regression: deleting the last remaining annotation in the annotations reading view (ADR 0041
    // "Highlights" reader) used to hard-crash — deleteAnnotation reloaded openBook(), which built a
    // synthesised Publication with an empty readingOrder, and Readium's EpubNavigatorFragment
    // throws on that. The fix keys off this predicate: when no live highlights remain, close the
    // reader (via ReaderNavEvent.CloseEmptyHighlights) instead of reloading. If the fix is
    // reverted, [reloadOrCloseHighlightsAfterDelete] returns false for the empty case and the
    // reader crashes again on device.
    @Test
    fun `highlightsShouldCloseAfterDelete is true when only a soft-deleted highlight remains`() {
        val rows = listOf(highlight("h1", "chA.xhtml", spineIndex = 0, deleted = true))
        assertTrue(highlightsShouldCloseAfterDelete(rows))
    }

    @Test
    fun `highlightsShouldCloseAfterDelete is true when the book has zero rows at all`() {
        assertTrue(highlightsShouldCloseAfterDelete(emptyList()))
    }

    @Test
    fun `highlightsShouldCloseAfterDelete is true when only bookmarks remain`() {
        // Bookmarks are stripped by buildChapterElisions, so a book whose only remaining rows are
        // bookmarks is empty from Highlights mode's perspective — the reader must close.
        val rows = listOf(
            highlight("b1", "chA.xhtml", spineIndex = 0, type = AnnotationEntity.TYPE_BOOKMARK),
        )
        assertTrue(highlightsShouldCloseAfterDelete(rows))
    }

    @Test
    fun `highlightsShouldCloseAfterDelete is false when at least one live highlight remains`() {
        val rows = listOf(
            highlight("h1", "chA.xhtml", spineIndex = 0),
            highlight("h2", "chB.xhtml", spineIndex = 1, deleted = true),
        )
        assertFalse(highlightsShouldCloseAfterDelete(rows))
    }

    // ---- Important #2: case-insensitive ReaderSource decoding -----------------------------

    // The regression this pins: the old `ReaderSource.valueOf(raw.replaceFirstChar(Char::uppercase))`
    // demoted "fullbook" (and would have demoted "FullBook", had any caller passed it) to FullBook
    // via a silent runCatching swallow, rather than actually matching the enum entry.
    @Test
    fun `decodeReaderSource matches highlights regardless of case`() {
        assertEquals(ReaderSource.Highlights, decodeReaderSource("highlights"))
        assertEquals(ReaderSource.Highlights, decodeReaderSource("Highlights"))
        assertEquals(ReaderSource.Highlights, decodeReaderSource("HIGHLIGHTS"))
    }

    @Test
    fun `decodeReaderSource matches fullbook regardless of case`() {
        assertEquals(ReaderSource.FullBook, decodeReaderSource("fullbook"))
        assertEquals(ReaderSource.FullBook, decodeReaderSource("FullBook"))
        assertEquals(ReaderSource.FullBook, decodeReaderSource("FULLBOOK"))
    }

    @Test
    fun `decodeReaderSource falls back to FullBook for null or garbage`() {
        assertEquals(ReaderSource.FullBook, decodeReaderSource(null))
        assertEquals(ReaderSource.FullBook, decodeReaderSource("nonsense"))
    }

    // ---- Critical #1: Highlights-mode highlight render resolver ----------------------------

    private fun annotation(
        id: String,
        snippet: String = "snippet-$id",
        color: String = "yellow",
        note: String? = null,
    ): Annotation = Annotation(
        id = id,
        sourceId = "S1",
        itemId = "B1",
        type = AnnotationEntity.TYPE_HIGHLIGHT,
        cfi = "epubcfi(/6/2!/dummy)",
        color = color,
        note = note,
        textSnippet = snippet,
        textBefore = "",
        textAfter = "",
        chapterHref = "chA.xhtml",
        spineIndex = 0,
        progression = 0.0,
        bookmarkTitle = "",
        createdAt = 0L,
        updatedAt = 0L,
    )

    // The regression this pins: highlightsAnnotationToRender must resolve a HighlightRender
    // against the SYNTHESISED chapter href ("highlights/ch$index.xhtml"), not the ABS EPUB's CFI —
    // annotationToRender (the FullBook resolver) requires `lifecycle.publication`/`lifecycle.zip()`,
    // both null in Highlights mode, so it can never be reused here.
    @Test
    fun `highlightsAnnotationToRender resolves the synthesised chapter href and preserves snippet text`() {
        val rows = listOf(
            highlight("h1", "chA.xhtml", spineIndex = 0),
            highlight("h2", "chB.xhtml", spineIndex = 1),
        )
        val chapters = buildChapterElisions(rows)

        val render = highlightsAnnotationToRender(
            chapters,
            annotation("h2", snippet = "the spice must flow"),
            urlFactory = ::testUrlFactory,
        )

        assertTrue(render.isNotEmpty())
        val single = render.first()
        assertEquals("highlights/ch1.xhtml", single.locator.href.toString())
        assertEquals("the spice must flow", single.locator.text.highlight)
        assertEquals("h2", single.id)
    }

    @Test
    fun `highlightsAnnotationToRender returns empty when the highlight is not in any chapter`() {
        val chapters = buildChapterElisions(listOf(highlight("h1", "chA.xhtml", spineIndex = 0)))
        assertTrue(highlightsAnnotationToRender(chapters, annotation("missing"), urlFactory = ::testUrlFactory).isEmpty())
    }

    @Test
    fun `highlightsAnnotationToRender preserves color and note`() {
        val chapters = buildChapterElisions(listOf(highlight("h1", "chA.xhtml", spineIndex = 0)))
        val render = highlightsAnnotationToRender(
            chapters,
            annotation("h1", color = "blue", note = "my thought"),
            urlFactory = ::testUrlFactory,
        ).first()
        assertEquals("blue", render.color)
        assertEquals("my thought", render.note)
    }

    // ---- Important #3: drop the restore-echo emission from the resume writer --------------

    // The regression this pins: the first href emission (the navigator restoring the locator
    // openBook() just set as initialLocator) must NOT reach the collector — only hrefs caused by
    // the user actually turning pages/chapters should. Before the fix, every Highlights-mode open
    // re-persisted the exact value it had just read from HighlightsResumeStore.
    @Test
    fun `highlightsResumeHrefUpdates drops the first (restore) emission`() = runBlocking {
        val hrefs = flowOf("highlights/ch0.xhtml", "highlights/ch1.xhtml", "highlights/ch2.xhtml")
        val result = highlightsResumeHrefUpdates(hrefs).toList()
        assertEquals(listOf("highlights/ch1.xhtml", "highlights/ch2.xhtml"), result)
    }

    @Test
    fun `highlightsResumeHrefUpdates collapses consecutive duplicate hrefs before dropping the restore`() = runBlocking {
        // Same chapter reported twice in a row (e.g. an intra-chapter position tick) must collapse
        // to one emission BEFORE the drop(1) removes the restore — otherwise a real chapter change
        // right after open could be mistaken for the restore-echo and silently dropped too.
        val hrefs = flowOf("highlights/ch0.xhtml", "highlights/ch0.xhtml", "highlights/ch1.xhtml")
        val result = highlightsResumeHrefUpdates(hrefs).toList()
        assertEquals(listOf("highlights/ch1.xhtml"), result)
    }

    @Test
    fun `highlightsResumeHrefUpdates emits nothing when only the restore fires`() = runBlocking {
        val hrefs = flowOf("highlights/ch0.xhtml")
        val result = highlightsResumeHrefUpdates(hrefs).toList()
        assertEquals(emptyList<String>(), result)
    }
}
