package com.riffle.shared.reader

import com.riffle.core.database.AnnotationEntity
import com.riffle.core.models.Annotation
import com.riffle.feature.reader.NavigatorDecoration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AnnotationDecorationMapperTest {

    private fun annotation(
        id: String = "ann-1",
        type: String = AnnotationEntity.TYPE_HIGHLIGHT,
        cfi: String = "epubcfi(/6/4!/4/2/16)",
        chapterHref: String = "ch1.xhtml",
        progression: Double = 0.5,
        color: String = "#FFFF00",
    ) = Annotation(
        id = id,
        sourceId = "src-1",
        itemId = "item-1",
        type = type,
        cfi = cfi,
        color = color,
        note = null,
        textSnippet = "",
        textBefore = "",
        textAfter = "",
        chapterHref = chapterHref,
        spineIndex = 0,
        progression = progression,
        bookmarkTitle = "",
        createdAt = 0L,
        updatedAt = 0L,
    )

    @Test
    fun highlightAnnotationMapsToHighlightDecoration() {
        val decoration = annotationToHighlightDecoration(annotation(type = AnnotationEntity.TYPE_HIGHLIGHT))
        assertEquals("ann-1", decoration?.id)
        assertEquals("#FFFF00", decoration?.color)
    }

    @Test
    fun bookmarkAnnotationReturnedNullFromHighlightMapper() {
        // bookmark type must not appear in highlights group
        val result = annotationToHighlightDecoration(annotation(type = AnnotationEntity.TYPE_BOOKMARK))
        assertNull(result)
    }

    @Test
    fun emphasisAnnotationReturnedNullFromHighlightMapper() {
        val result = annotationToHighlightDecoration(annotation(type = AnnotationEntity.TYPE_EMPHASIS))
        assertNull(result)
    }

    @Test
    fun blankColorFallsBackToYellow() {
        val decoration = annotationToHighlightDecoration(annotation(color = ""))
        assertEquals("#FFFF00", decoration?.color)
    }

    @Test
    fun highlightColorIsPreservedWhenNonBlank() {
        val decoration = annotationToHighlightDecoration(annotation(color = "#FF0000"))
        assertEquals("#FF0000", decoration?.color)
    }

    @Test
    fun bookmarkMapperProducesBookmarkDecoration() {
        val decoration = annotationToBookmarkDecoration(annotation(id = "bm-1"))
        assertEquals("bm-1", decoration.id)
    }

    @Test
    fun locatorJsonContainsHrefAndCfiFragment() {
        val decoration = annotationToHighlightDecoration(
            annotation(cfi = "epubcfi(/6/4!/4/2/16)", chapterHref = "ch1.xhtml")
        )
        val locator = decoration?.locatorJson ?: ""
        assertEquals(true, locator.contains("ch1.xhtml"), "locator must contain href: $locator")
        assertEquals(true, locator.contains("/4/2/16"), "locator must contain CFI fragment: $locator")
    }
}
