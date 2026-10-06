package com.riffle.feature.reader.highlights

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals

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
}
