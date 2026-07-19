package com.riffle.core.data

import com.riffle.core.database.AnnotationEntity
import com.riffle.core.models.Annotation
import com.riffle.core.models.EmbeddedFigure
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Entity <-> domain round-trip coverage for the image-annotation fields added to
 * [AnnotationEntity] / [Annotation] (embeddedFigures, imageHref, imageSvg).
 */
class AnnotationMappingTest {

    private fun baseAnnotation(
        embeddedFigures: List<EmbeddedFigure>? = null,
        imageHref: String? = null,
        imageSvg: String? = null,
    ) = Annotation(
        id = "ann-1",
        sourceId = "source-1",
        itemId = "item-1",
        type = AnnotationEntity.TYPE_HIGHLIGHT,
        cfi = "epubcfi(/6/4!/4/2,/1:0,/1:10)",
        color = AnnotationEntity.COLOR_YELLOW,
        note = null,
        textSnippet = "snippet",
        textBefore = "before",
        textAfter = "after",
        chapterHref = "chapter1.xhtml",
        spineIndex = 0,
        progression = 0.0,
        bookmarkTitle = "",
        createdAt = 0L,
        updatedAt = 0L,
        embeddedFigures = embeddedFigures,
        imageHref = imageHref,
        imageSvg = imageSvg,
    )

    @Test
    fun `mapper preserves embedded figures round-trip`() {
        val figures = listOf(
            EmbeddedFigure(href = "img/graph.png", svg = null, caption = "Fig 1", order = 0),
            EmbeddedFigure(href = null, svg = "<svg/>", caption = "", order = 1),
        )
        val entity = baseAnnotation(embeddedFigures = figures).toEntity()
        val back = entity.toDomain()

        assertEquals(figures, back.embeddedFigures)
    }

    @Test
    fun `mapper preserves embedded figures order after out-of-order input`() {
        val figures = listOf(
            EmbeddedFigure(href = null, svg = "<svg/>", caption = "Second", order = 1),
            EmbeddedFigure(href = "img/graph.png", svg = null, caption = "First", order = 0),
        )
        val entity = baseAnnotation(embeddedFigures = figures).toEntity()
        val back = entity.toDomain()

        assertEquals(listOf("First", "Second"), back.embeddedFigures!!.map { it.caption })
    }

    @Test
    fun `null embeddedFigures maps to null, not empty list`() {
        val entity = baseAnnotation(embeddedFigures = null).toEntity()

        assertNull(entity.embeddedFigures)
        assertNull(entity.toDomain().embeddedFigures)
    }

    @Test
    fun `empty embeddedFigures list maps to null column`() {
        val entity = baseAnnotation(embeddedFigures = emptyList()).toEntity()

        assertNull(entity.embeddedFigures)
        assertNull(entity.toDomain().embeddedFigures)
    }

    @Test
    fun `imageHref and imageSvg map straight through`() {
        val entity = baseAnnotation(imageHref = "img/photo.jpg", imageSvg = "<svg>x</svg>").toEntity()
        val back = entity.toDomain()

        assertEquals("img/photo.jpg", entity.imageHref)
        assertEquals("<svg>x</svg>", entity.imageSvg)
        assertEquals("img/photo.jpg", back.imageHref)
        assertEquals("<svg>x</svg>", back.imageSvg)
    }

    @Test
    fun `null imageHref and imageSvg round-trip as null`() {
        val entity = baseAnnotation().toEntity()
        val back = entity.toDomain()

        assertNull(entity.imageHref)
        assertNull(entity.imageSvg)
        assertNull(back.imageHref)
        assertNull(back.imageSvg)
    }
}
