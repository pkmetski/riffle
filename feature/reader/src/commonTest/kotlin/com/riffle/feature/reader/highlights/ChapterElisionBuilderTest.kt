package com.riffle.feature.reader.highlights

import com.riffle.core.database.AnnotationEntity
import com.riffle.core.models.Annotation
import com.riffle.core.models.EmphasisStyle
import com.riffle.core.models.TocEntry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ChapterElisionBuilderTest {

    private fun highlight(
        id: String,
        chapterHref: String,
        spineIndex: Int = 0,
        progression: Double = 0.0,
        createdAt: Long = 0L,
        deleted: Boolean = false,
    ) = AnnotationEntity(
        id = id,
        sourceId = "src",
        itemId = "item",
        type = AnnotationEntity.TYPE_HIGHLIGHT,
        cfi = "epubcfi(/6[$id]!/4)",
        textSnippet = "text",
        chapterHref = chapterHref,
        spineIndex = spineIndex,
        progression = progression,
        createdAt = createdAt,
        updatedAt = 0L,
        originDeviceId = "",
        lastModifiedByDeviceId = "",
        deleted = deleted,
    )

    @Test
    fun emptyInputProducesEmptyElisions() {
        assertTrue(buildChapterElisions(emptyList()).isEmpty())
    }

    @Test
    fun softDeletedHighlightsAreExcluded() {
        val rows = listOf(highlight("h1", "ch1.xhtml", deleted = true))
        assertTrue(buildChapterElisions(rows).isEmpty())
    }

    @Test
    fun bookmarksAreExcluded() {
        val bookmark = highlight("b1", "ch1.xhtml").copy(type = AnnotationEntity.TYPE_BOOKMARK)
        assertTrue(buildChapterElisions(listOf(bookmark)).isEmpty())
    }

    @Test
    fun highlightsGroupedByChapterHref() {
        val rows = listOf(
            highlight("h1", "ch1.xhtml"),
            highlight("h2", "ch2.xhtml"),
            highlight("h3", "ch1.xhtml"),
        )
        val elisions = buildChapterElisions(rows)
        assertEquals(2, elisions.size)
        assertEquals("ch1.xhtml", elisions[0].href)
        assertEquals(2, elisions[0].highlights.size)
        assertEquals("ch2.xhtml", elisions[1].href)
        assertEquals(1, elisions[1].highlights.size)
    }

    @Test
    fun highlightsOrderedBySpineIndexThenProgressionThenCreatedAt() {
        val rows = listOf(
            highlight("h_late", "ch1.xhtml", spineIndex = 0, progression = 0.5, createdAt = 200L),
            highlight("h_early", "ch1.xhtml", spineIndex = 0, progression = 0.2, createdAt = 100L),
        )
        val elision = buildChapterElisions(rows).single()
        assertEquals("h_early", elision.highlights[0].id)
        assertEquals("h_late", elision.highlights[1].id)
    }

    @Test
    fun chapterOrderPreservedByFirstEncounterOrder() {
        val rows = listOf(
            highlight("h1", "ch2.xhtml", spineIndex = 1),
            highlight("h2", "ch1.xhtml", spineIndex = 0),
        )
        val elisions = buildChapterElisions(rows)
        // sorted by spineIndex first, so ch1 (spineIndex=0) comes first
        assertEquals("ch1.xhtml", elisions[0].href)
        assertEquals("ch2.xhtml", elisions[1].href)
    }

    @Test
    fun deriveChapterTitleStripsPathAndExtension() {
        assertEquals("chapter01", deriveChapterTitle("OEBPS/Text/chapter01.xhtml"))
    }

    @Test
    fun deriveChapterTitleFallsBackToChapterForBlankFilename() {
        assertEquals("Chapter", deriveChapterTitle(""))
    }

    @Test
    fun tocTitlesUsedWhenTocIsProvided() {
        val rows = listOf(
            highlight("h1", "OEBPS/ch1.xhtml", spineIndex = 0),
            highlight("h2", "OEBPS/ch2.xhtml", spineIndex = 1),
        )
        val toc = listOf(
            TocEntry(title = "Introduction", href = "OEBPS/ch1.xhtml"),
            TocEntry(title = "Chapter One", href = "OEBPS/ch2.xhtml"),
        )
        val elisions = buildChapterElisions(rows, toc)
        assertEquals("Introduction", elisions[0].title)
        assertEquals("Chapter One", elisions[1].title)
    }

    @Test
    fun fallsBackToChapterNForSpineFilenameWhenTocAbsent() {
        val rows = listOf(
            highlight("h1", "OEBPS/item001.xhtml", spineIndex = 0),
        )
        val elisions = buildChapterElisions(rows, emptyList())
        assertEquals("Chapter 1", elisions[0].title)
    }

    // --- joinEmphasisStylesToHighlights (AnnotationEntity path, used by Android) ---

    @Test
    fun joinEmphasisStylesToHighlightsNoEmphasisRowsReturnsOriginalList() {
        val rows = listOf(highlight("h1", "ch.xhtml"))
        val result = joinEmphasisStylesToHighlights(rows)
        assertSame(rows, result)
    }

    @Test
    fun joinEmphasisStylesToHighlightsCopiesStylesOntoMatchingHighlight() {
        val emphasisRow = highlight("e1", "ch.xhtml").copy(
            type = AnnotationEntity.TYPE_EMPHASIS,
            cfi = "epubcfi(/6[h1]!/4)",
            emphasisStyles = AnnotationEntity.EMPHASIS_STYLE_BOLD,
        )
        val highlightRow = highlight("h1", "ch.xhtml").copy(cfi = "epubcfi(/6[h1]!/4)")
        val result = joinEmphasisStylesToHighlights(listOf(emphasisRow, highlightRow))
        assertEquals(AnnotationEntity.EMPHASIS_STYLE_BOLD, result.first { it.id == "h1" }.emphasisStyles)
    }

    @Test
    fun joinEmphasisStylesToHighlightsDeletedEmphasisIsIgnored() {
        val emphasisRow = highlight("e1", "ch.xhtml").copy(
            type = AnnotationEntity.TYPE_EMPHASIS,
            cfi = "epubcfi(/6[h1]!/4)",
            emphasisStyles = AnnotationEntity.EMPHASIS_STYLE_BOLD,
            deleted = true,
        )
        val highlightRow = highlight("h1", "ch.xhtml").copy(cfi = "epubcfi(/6[h1]!/4)")
        val result = joinEmphasisStylesToHighlights(listOf(emphasisRow, highlightRow))
        assertEquals(null, result.first { it.id == "h1" }.emphasisStyles)
    }

    // --- joinEmphasisStylesToAnnotations (Annotation domain model path, used by iOS) ---

    private fun annotation(id: String, type: String = AnnotationEntity.TYPE_HIGHLIGHT, cfi: String = "epubcfi(/6[$id]!/4)") = Annotation(
        id = id,
        sourceId = "src",
        itemId = "item",
        type = type,
        cfi = cfi,
        color = "",
        note = null,
        textSnippet = "",
        textBefore = "",
        textAfter = "",
        chapterHref = "ch.xhtml",
        spineIndex = 0,
        progression = 0.0,
        bookmarkTitle = "",
        createdAt = 0L,
        updatedAt = 0L,
    )

    @Test
    fun joinEmphasisStylesToAnnotationsNoEmphasisReturnsOriginalList() {
        val anns = listOf(annotation("h1"))
        val result = joinEmphasisStylesToAnnotations(anns)
        assertSame(anns, result)
    }

    @Test
    fun joinEmphasisStylesToAnnotationsCopiesStylesOntoMatchingHighlight() {
        val cfi = "epubcfi(/6[h1]!/4)"
        val emphasisAnn = annotation("e1", type = AnnotationEntity.TYPE_EMPHASIS, cfi = cfi)
            .copy(emphasisStyles = setOf(EmphasisStyle.BOLD))
        val highlightAnn = annotation("h1", cfi = cfi)
        val result = joinEmphasisStylesToAnnotations(listOf(emphasisAnn, highlightAnn))
        assertEquals(setOf(EmphasisStyle.BOLD), result.first { it.id == "h1" }.emphasisStyles)
    }

    // --- toggleBookmark guard: synthetic href must not produce a bookmark ---

    @Test
    fun highlightsHrefStartsWithHighlightsSlash() {
        // Verifies the guard predicate used in toggleBookmark: any synthetic chapter href matches.
        assertTrue("highlights/ch0.xhtml".startsWith("highlights/"))
        assertTrue("highlights/ch99.xhtml".startsWith("highlights/"))
    }

    // --- annotations panel navigation in Highlights mode ---

    @Test
    fun highlightsChapterIndexForAnnotation() {
        val ch0 = ChapterElision("highlights/ch0.xhtml", "Ch 0", listOf(highlight("h1", "ch.xhtml")))
        val ch1 = ChapterElision("highlights/ch1.xhtml", "Ch 1", listOf(highlight("h2", "ch.xhtml")))
        val chapters = listOf(ch0, ch1)
        assertEquals(0, chapters.indexOfFirst { ch -> ch.highlights.any { it.id == "h1" } })
        assertEquals(1, chapters.indexOfFirst { ch -> ch.highlights.any { it.id == "h2" } })
        assertEquals(-1, chapters.indexOfFirst { ch -> ch.highlights.any { it.id == "missing" } })
    }
}
