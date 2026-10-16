package com.riffle.feature.reader.highlights

import com.riffle.core.database.AnnotationEntity
import com.riffle.core.models.TocEntry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HighlightsViewLogicTest {

    // ---- looksUnhelpfulTitle ------------------------------------------------------------------

    @Test
    fun looksUnhelpfulTitleRejectsBlankBareCHapterUUIDAndSpineFilenames() {
        assertTrue(looksUnhelpfulTitle(""))
        assertTrue(looksUnhelpfulTitle("   "))
        assertTrue(looksUnhelpfulTitle("Chapter"))
        assertTrue(looksUnhelpfulTitle("chapter"))
        assertTrue(looksUnhelpfulTitle("6ba7b810-9dad-11d1-80b4-00c04fd430c8"))
        assertTrue(looksUnhelpfulTitle("part0010"))
        assertTrue(looksUnhelpfulTitle("item003"))
        assertTrue(looksUnhelpfulTitle("text00001"))
        assertTrue(looksUnhelpfulTitle("SECTION_012"))
        assertTrue(looksUnhelpfulTitle("ch03"))
        assertTrue(looksUnhelpfulTitle("ch06"))
        assertTrue(looksUnhelpfulTitle("ch10"))
    }

    @Test
    fun looksUnhelpfulTitleAcceptsRealChapterTitles() {
        assertFalse(looksUnhelpfulTitle("The Nature of Complexity"))
        assertFalse(looksUnhelpfulTitle("Introduction"))
        assertFalse(looksUnhelpfulTitle("Epilogue"))
        assertFalse(looksUnhelpfulTitle("Act2"))
        assertFalse(looksUnhelpfulTitle("Part3"))
    }

    // ---- elidedChapterTitle ------------------------------------------------------------------

    @Test
    fun elidedChapterTitleFallsBackToChapterNWhenTocEmptyAndDerivedIsSpineFilename() {
        assertEquals("Chapter 1", elidedChapterTitle("OEBPS/part0010.xhtml", "part0010", emptyList(), 0))
        assertEquals("Chapter 3", elidedChapterTitle("OEBPS/item003.xhtml", "item003", emptyList(), 2))
        assertEquals("Chapter 1", elidedChapterTitle("OEBPS/ch06.xhtml", "ch06", emptyList(), 0))
        assertEquals("Chapter 3", elidedChapterTitle("OEBPS/ch10.xhtml", "ch10", emptyList(), 2))
    }

    @Test
    fun elidedChapterTitleUsesTocTitleWhenAvailable() {
        val toc = listOf(TocEntry(href = "OEBPS/part0010.xhtml", title = "Complexity", children = emptyList()))
        assertEquals("Complexity", elidedChapterTitle("OEBPS/part0010.xhtml", "part0010", toc, 0))
    }

    // ---- resolveChapterTitle -----------------------------------------------------------------

    @Test
    fun resolveChapterTitlePrefersTocEntryTitle() {
        val toc = listOf(TocEntry(href = "OEBPS/part0007.xhtml", title = "The Nature of Complexity", children = emptyList()))
        assertEquals("The Nature of Complexity", resolveChapterTitle("OEBPS/part0007.xhtml", toc))
    }

    @Test
    fun resolveChapterTitleStripsFragment() {
        val toc = listOf(TocEntry(href = "OEBPS/part0008.xhtml#heading", title = "Modules", children = emptyList()))
        assertEquals("Modules", resolveChapterTitle("OEBPS/part0008.xhtml", toc))
    }

    @Test
    fun resolveChapterTitleReturnsNullWhenNoMatch() {
        assertNull(resolveChapterTitle("OEBPS/part0009.xhtml", emptyList()))
    }

    @Test
    fun resolveChapterTitleReturnsNullForBlankTocTitle() {
        val toc = listOf(TocEntry(href = "OEBPS/part0010.xhtml", title = "", children = emptyList()))
        assertNull(resolveChapterTitle("OEBPS/part0010.xhtml", toc))
        assertEquals("Chapter 1", elidedChapterTitle("OEBPS/part0010.xhtml", "part0010", toc, 0))
    }

    @Test
    fun resolveChapterTitleMatchesNestedEntries() {
        val toc = listOf(
            TocEntry(
                href = "OEBPS/part0001.xhtml",
                title = "Part One",
                children = listOf(
                    TocEntry(href = "OEBPS/part0002.xhtml", title = "Nested Chapter", children = emptyList()),
                ),
            ),
        )
        assertEquals("Nested Chapter", resolveChapterTitle("OEBPS/part0002.xhtml", toc))
    }

    // ---- highlightsResumeChapterHref ---------------------------------------------------------

    private fun makeAnnotation(id: String, chapterHref: String) = AnnotationEntity(
        id = id,
        sourceId = "s",
        itemId = "i",
        type = AnnotationEntity.TYPE_HIGHLIGHT,
        cfi = "epubcfi(/6/$id)",
        textSnippet = "text",
        chapterHref = chapterHref,
        spineIndex = 0,
        progression = 0.1,
        createdAt = 1000L,
        updatedAt = 1000L,
        originDeviceId = "",
        lastModifiedByDeviceId = "",
    )

    // Two chapters: chA with two highlights (h1 at prog=0.1, h2 at prog=0.5), chB with only h3.
    private fun makeTwoChapterElisions(): List<ChapterElision> {
        val h1 = makeAnnotation("h1", "chA.xhtml").copy(progression = 0.1, createdAt = 1L)
        val h2 = makeAnnotation("h2", "chA.xhtml").copy(progression = 0.5, createdAt = 2L)
        val h3 = makeAnnotation("h3", "chB.xhtml").copy(spineIndex = 1)
        return listOf(
            ChapterElision(href = "chA.xhtml", title = "Chapter A", highlights = listOf(h1, h2)),
            ChapterElision(href = "chB.xhtml", title = "Chapter B", highlights = listOf(h3)),
        )
    }

    @Test
    fun highlightsResumeChapterHrefResolvesCorrectSynthesisedHref() {
        val chapters = makeTwoChapterElisions()
        assertEquals("highlights/ch1.xhtml", highlightsResumeChapterHref(chapters, "h3", readingOrderSize = 2))
    }

    @Test
    fun highlightsResumeChapterHrefReturnsNullWhenHighlightMissing() {
        val chapters = makeTwoChapterElisions()
        assertNull(highlightsResumeChapterHref(chapters, "deleted-id", readingOrderSize = 1))
    }

    @Test
    fun highlightsResumeChapterHrefReturnsNullWhenIndexOutOfRange() {
        val chapters = listOf(
            ChapterElision(href = "chA.xhtml", title = "Chapter A", highlights = listOf(makeAnnotation("h1", "chA.xhtml"))),
        )
        assertNull(highlightsResumeChapterHref(chapters, "h1", readingOrderSize = 0))
    }

    // ---- highlightsResumeAnnotationIdForHref ------------------------------------------------

    @Test
    fun highlightsResumeAnnotationIdForHrefIsInverseOfResumeChapterHref() {
        val chapters = makeTwoChapterElisions()
        // chB is index 1 → first highlight is h3
        assertEquals("h3", highlightsResumeAnnotationIdForHref(chapters, "highlights/ch1.xhtml"))
        // chA is index 0 → first highlight by (spineIndex, progression) is h1
        assertEquals("h1", highlightsResumeAnnotationIdForHref(chapters, "highlights/ch0.xhtml"))
    }

    @Test
    fun highlightsResumeAnnotationIdForHrefReturnsNullForUnrecognisedHref() {
        val chapters = makeTwoChapterElisions()
        assertNull(highlightsResumeAnnotationIdForHref(chapters, "not-a-highlights-href.xhtml"))
        assertNull(highlightsResumeAnnotationIdForHref(chapters, "highlights/ch7.xhtml"))
    }
}
