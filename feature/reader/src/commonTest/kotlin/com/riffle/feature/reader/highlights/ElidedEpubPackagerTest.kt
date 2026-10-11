package com.riffle.feature.reader.highlights

import com.riffle.core.database.AnnotationEntity
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ElidedEpubPackagerTest {

    private fun singleChapter(): List<ChapterElision> = listOf(
        ChapterElision(href = "ch1.xhtml", title = "Chapter 1", highlights = emptyList()),
    )

    @Test
    fun chapterHrefProducesHighlightsSubpath() {
        assertEquals("highlights/ch0.xhtml", ElidedEpubPackager.chapterHref(0))
        assertEquals("highlights/ch3.xhtml", ElidedEpubPackager.chapterHref(3))
    }

    @Test
    fun buildOpfContainsItemIdAndTitle() {
        val opf = ElidedEpubPackager.buildOpf("My Book", "item123", singleChapter())
        assertContains(opf, "item123")
        assertContains(opf, "My Book")
    }

    @Test
    fun buildOpfContainsManifestAndSpineForEachChapter() {
        val chapters = listOf(
            ChapterElision(href = "ch0.xhtml", title = "A", highlights = emptyList()),
            ChapterElision(href = "ch1.xhtml", title = "B", highlights = emptyList()),
        )
        val opf = ElidedEpubPackager.buildOpf("Title", "id", chapters)
        assertContains(opf, """id="ch0" href="highlights/ch0.xhtml"""")
        assertContains(opf, """id="ch1" href="highlights/ch1.xhtml"""")
        assertContains(opf, """<itemref idref="ch0"/>""")
        assertContains(opf, """<itemref idref="ch1"/>""")
    }

    @Test
    fun buildOpfEscapesXmlCharsInTitle() {
        val opf = ElidedEpubPackager.buildOpf("A & B", "id", singleChapter())
        assertContains(opf, "A &amp; B")
    }

    @Test
    fun buildNavContainsTocLinks() {
        val chapters = listOf(
            ChapterElision(href = "ch0.xhtml", title = "Part 1", highlights = emptyList()),
        )
        val nav = ElidedEpubPackager.buildNav("Title", chapters)
        assertContains(nav, """href="highlights/ch0.xhtml">Part 1""")
    }

    @Test
    fun buildContainerXmlPointsAtContentOpf() {
        assertContains(ElidedEpubPackager.buildContainerXml(), "content.opf")
    }

    @Test
    fun mimeTypeContentIsEpubZip() {
        assertEquals("application/epub+zip", ElidedEpubPackager.MIME_TYPE_CONTENT)
    }

    // ── mimeForHref ───────────────────────────────────────────────────────────────────────────────

    @Test
    fun mimeForHrefReturnsPngForPngExtension() {
        assertEquals("image/png", mimeForHref("images/cover.png"))
    }

    @Test
    fun mimeForHrefReturnsGifForGifExtension() {
        assertEquals("image/gif", mimeForHref("assets/anim.gif"))
    }

    @Test
    fun mimeForHrefReturnsWebpForWebpExtension() {
        assertEquals("image/webp", mimeForHref("img/photo.webp"))
    }

    @Test
    fun mimeForHrefReturnsSvgForSvgXmlExtension() {
        assertEquals("image/svg+xml", mimeForHref("images/diagram.svg"))
    }

    @Test
    fun mimeForHrefFallsBackToJpegForJpgExtension() {
        assertEquals("image/jpeg", mimeForHref("images/fig1.jpg"))
    }

    @Test
    fun mimeForHrefFallsBackToJpegForJpegExtension() {
        assertEquals("image/jpeg", mimeForHref("images/fig1.jpeg"))
    }

    @Test
    fun mimeForHrefStripsQueryStringBeforeMatchingExtension() {
        assertEquals("image/png", mimeForHref("images/cover.png?v=2"))
    }

    @Test
    fun mimeForHrefIsCaseInsensitive() {
        assertEquals("image/png", mimeForHref("images/COVER.PNG"))
    }

    // ── figureHrefsFromChapters ───────────────────────────────────────────────────────────────────

    private fun annotationWithImageHref(id: String, imageHref: String?) = AnnotationEntity(
        id = id,
        sourceId = "src",
        itemId = "item",
        type = AnnotationEntity.TYPE_HIGHLIGHT,
        cfi = "epubcfi(/6[$id]!/4)",
        textSnippet = "text",
        chapterHref = "ch1.xhtml",
        createdAt = 0L,
        updatedAt = 0L,
        originDeviceId = "",
        lastModifiedByDeviceId = "",
        imageHref = imageHref,
    )

    @Test
    fun figureHrefsFromChaptersReturnsEmptyForNoAnnotations() {
        val chapters = listOf(ChapterElision("ch1.xhtml", "Ch 1", emptyList()))
        assertTrue(figureHrefsFromChapters(chapters).isEmpty())
    }

    @Test
    fun figureHrefsFromChaptersCollectsImageHrefs() {
        val annotation = annotationWithImageHref("h1", "images/fig1.png")
        val chapters = listOf(ChapterElision("ch1.xhtml", "Ch 1", listOf(annotation)))
        assertEquals(listOf("images/fig1.png"), figureHrefsFromChapters(chapters))
    }

    @Test
    fun figureHrefsFromChaptersDeduplicatesCrossChapterHrefs() {
        val a1 = annotationWithImageHref("h1", "images/shared.png")
        val a2 = annotationWithImageHref("h2", "images/shared.png")
        val chapters = listOf(
            ChapterElision("ch1.xhtml", "Ch 1", listOf(a1)),
            ChapterElision("ch2.xhtml", "Ch 2", listOf(a2)),
        )
        assertEquals(listOf("images/shared.png"), figureHrefsFromChapters(chapters))
    }

    @Test
    fun figureHrefsFromChaptersSkipsAnnotationsWithNoImageHref() {
        val annotation = annotationWithImageHref("h1", null)
        val chapters = listOf(ChapterElision("ch1.xhtml", "Ch 1", listOf(annotation)))
        assertTrue(figureHrefsFromChapters(chapters).isEmpty())
    }
}
