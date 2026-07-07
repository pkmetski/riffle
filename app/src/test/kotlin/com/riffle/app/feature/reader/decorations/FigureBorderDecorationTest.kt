package com.riffle.app.feature.reader.decorations

import com.riffle.core.database.AnnotationEntity
import com.riffle.core.domain.Annotation
import com.riffle.core.domain.EmbeddedFigure
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FigureBorderDecorationTest {

    @Test
    fun `newest annotation wins when two cover the same figure`() {
        val older = imageAnnotation(id = "a", imageHref = "g.png", color = "yellow", updatedAt = 100)
        val newer = imageAnnotation(id = "b", imageHref = "g.png", color = "green", updatedAt = 200)

        val rules = FigureBorderDecoration.buildCssRules(listOf(older, newer))

        assertEquals(1, rules.count { it.contains("g.png") })
        val rule = rules.single { it.contains("g.png") }
        // green = 0x8034D399 -> rgb(52,211,153); reverting maxByOrNull{updatedAt} to e.g. minByOrNull
        // or firstOrNull would flip this to yellow (251,191,36) and fail.
        assertTrue(rule.contains("52,211,153"))
        assertFalse(rule.contains("251,191,36"))
    }

    @Test
    fun `emits one rule per distinct figure`() {
        val a = imageAnnotation(id = "a", imageHref = "one.png", color = "yellow")
        val b = imageAnnotation(id = "b", imageHref = "two.png", color = "green")

        val rules = FigureBorderDecoration.buildCssRules(listOf(a, b))

        assertEquals(2, rules.size)
    }

    @Test
    fun `TYPE_HIGHLIGHT with embedded figures produces border rules`() {
        val hl = highlightAnnotation(
            id = "h1",
            color = "yellow",
            embedded = listOf(EmbeddedFigure(href = "g.png", svg = null, caption = "cap", order = 0)),
        )

        val rules = FigureBorderDecoration.buildCssRules(listOf(hl))

        assertEquals(1, rules.size)
        assertTrue(rules.single().contains("g.png"))
        // yellow = 0x80FBBF24 -> rgb(251,191,36)
        assertTrue(rules.single().contains("251,191,36"))
    }

    @Test
    fun `pure SVG annotation produces no rule`() {
        val svgImage = imageAnnotation(id = "s1", imageHref = null, imageSvg = "<svg></svg>", color = "yellow")
        val svgEmbedded = highlightAnnotation(
            id = "h2",
            color = "green",
            embedded = listOf(EmbeddedFigure(href = null, svg = "<svg></svg>", caption = "cap", order = 0)),
        )

        val rules = FigureBorderDecoration.buildCssRules(listOf(svgImage, svgEmbedded))

        assertTrue(rules.isEmpty())
    }

    @Test
    fun `rule includes the annotation color`() {
        val a = imageAnnotation(id = "a", imageHref = "g.png", color = "blue")

        val rules = FigureBorderDecoration.buildCssRules(listOf(a))

        // blue = 0x8038BDF8 -> rgb(56,189,248); reverting the color plumbing to a hardcoded
        // default (e.g. always yellow) would flip this assertion red.
        assertTrue(rules.single().contains("56,189,248"))
    }

    private fun imageAnnotation(
        id: String,
        imageHref: String? = null,
        imageSvg: String? = null,
        color: String = "yellow",
        updatedAt: Long = 0L,
    ): Annotation = baseAnnotation(
        id = id,
        type = AnnotationEntity.TYPE_IMAGE,
        color = color,
        updatedAt = updatedAt,
        imageHref = imageHref,
        imageSvg = imageSvg,
    )

    private fun highlightAnnotation(
        id: String,
        color: String = "yellow",
        embedded: List<EmbeddedFigure>? = null,
        updatedAt: Long = 0L,
    ): Annotation = baseAnnotation(
        id = id,
        type = AnnotationEntity.TYPE_HIGHLIGHT,
        color = color,
        updatedAt = updatedAt,
        embeddedFigures = embedded,
    )

    private fun baseAnnotation(
        id: String,
        type: String,
        color: String,
        updatedAt: Long,
        imageHref: String? = null,
        imageSvg: String? = null,
        embeddedFigures: List<EmbeddedFigure>? = null,
    ): Annotation = Annotation(
        id = id,
        sourceId = "S1",
        itemId = "B1",
        type = type,
        cfi = "epubcfi(/6/2!/dummy)",
        color = color,
        note = null,
        textSnippet = "",
        textBefore = "",
        textAfter = "",
        chapterHref = "chA.xhtml",
        spineIndex = 0,
        progression = 0.0,
        bookmarkTitle = "",
        createdAt = 0L,
        updatedAt = updatedAt,
        embeddedFigures = embeddedFigures,
        imageHref = imageHref,
        imageSvg = imageSvg,
    )
}
