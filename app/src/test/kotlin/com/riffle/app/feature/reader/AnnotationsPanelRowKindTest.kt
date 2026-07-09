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

    // Regression pin: a HIGHLIGHT that enclosed a figure with captured bytes must render as
    // the Image row so the panel shows the figure's thumbnail. Falling back to the plain
    // color-dot row is the old bug — the annotations list looked like a text-only highlight
    // and hid the diagram the user actually wanted to see.
    @Test
    fun `highlight with embedded figure bytes routes to Image row`() {
        val ann = annotationOfType(AnnotationEntity.TYPE_HIGHLIGHT).copy(
            embeddedFigures = listOf(
                com.riffle.core.domain.EmbeddedFigure(
                    href = "images/eq.png",
                    svg = null,
                    caption = "",
                    order = 0,
                    imageBytes = "data:image/jpeg;base64,AAAA",
                ),
            ),
        )
        assertEquals(RowKind.Image, rowKindFor(ann))
    }

    // Highlights with embedded figures BUT no captured bytes stay as the color-dot row — the
    // Image row without a bitmap would fall back to a generic icon that looks the same as (or
    // worse than) the dot.
    @Test
    fun `highlight with embedded figure but no bytes stays as Highlight row`() {
        val ann = annotationOfType(AnnotationEntity.TYPE_HIGHLIGHT).copy(
            embeddedFigures = listOf(
                com.riffle.core.domain.EmbeddedFigure(
                    href = "images/eq.png",
                    svg = null,
                    caption = "",
                    order = 0,
                    imageBytes = null,
                ),
            ),
        )
        assertEquals(RowKind.Highlight, rowKindFor(ann))
    }

    @Test
    fun `unknown type falls back to Highlight row`() {
        assertEquals(RowKind.Highlight, rowKindFor(annotationOfType("SOMETHING_ELSE")))
    }
}
