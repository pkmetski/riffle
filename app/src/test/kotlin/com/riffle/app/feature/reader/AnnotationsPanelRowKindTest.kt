package com.riffle.app.feature.reader

import com.riffle.core.database.AnnotationEntity
import com.riffle.core.domain.Annotation
import org.junit.Assert.assertEquals
import org.junit.Test

class AnnotationsPanelRowKindTest {

    private fun annotationOfType(type: String) = Annotation(
        id = "ann-1",
        sourceId = "src-1",
        itemId = "item-1",
        type = type,
        cfi = "epubcfi(/6/4!/4/2/1:0)",
        color = "yellow",
        note = null,
        textSnippet = "Figure 3: a diagram of the widget assembly",
        textBefore = "",
        textAfter = "",
        chapterHref = "chapter1.xhtml",
        spineIndex = 0,
        progression = 0.0,
        bookmarkTitle = "",
        createdAt = 0L,
        updatedAt = 0L,
    )

    @Test
    fun `image annotation routes to Image row`() {
        assertEquals(RowKind.Image, rowKindFor(annotationOfType(AnnotationEntity.TYPE_IMAGE)))
    }

    @Test
    fun `highlight annotation routes to Highlight row`() {
        assertEquals(RowKind.Highlight, rowKindFor(annotationOfType(AnnotationEntity.TYPE_HIGHLIGHT)))
    }

    @Test
    fun `bookmark annotation routes to Bookmark row`() {
        assertEquals(RowKind.Bookmark, rowKindFor(annotationOfType(AnnotationEntity.TYPE_BOOKMARK)))
    }

    @Test
    fun `unknown type falls back to Highlight row`() {
        assertEquals(RowKind.Highlight, rowKindFor(annotationOfType("SOMETHING_ELSE")))
    }
}
