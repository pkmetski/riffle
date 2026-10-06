package com.riffle.feature.reader.highlights

import com.riffle.core.database.AnnotationEntity
import kotlin.test.Test
import kotlin.test.assertEquals
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
}
