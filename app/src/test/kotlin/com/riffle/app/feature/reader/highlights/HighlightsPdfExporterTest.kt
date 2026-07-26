package com.riffle.app.feature.reader.highlights

import com.riffle.core.database.AnnotationEntity
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HighlightsPdfExporterTest {

    private val factory = HighlightsPublicationFactory()

    private fun highlight(id: String, snippet: String): AnnotationEntity = AnnotationEntity(
        id = id,
        sourceId = "S1",
        itemId = "B1",
        type = AnnotationEntity.TYPE_HIGHLIGHT,
        cfi = "epubcfi(/6/2!/dummy)",
        textSnippet = snippet,
        note = null,
        color = AnnotationEntity.COLOR_YELLOW,
        chapterHref = "ch0.xhtml",
        spineIndex = 0,
        progression = 0.0,
        createdAt = 0L,
        updatedAt = 0L,
        originDeviceId = "test",
        lastModifiedByDeviceId = "test",
        originFontFamily = null,
        textSnippetHtml = null,
        emphasisStyles = null,
    )

    @Test
    fun combinedHtml_containsAllChapterTitles() {
        val html = buildCombinedHtml(
            factory = factory,
            chapters = listOf(
                ChapterElision("ch1.xhtml", "Chapter One", listOf(highlight("h1", "text"))),
                ChapterElision("ch2.xhtml", "Chapter Two", listOf(highlight("h2", "more text"))),
            ),
            bookTitle = "My Book",
            figureBytesByHref = emptyMap(),
            publisherFontFaceCss = "",
            bookBodyFontFamily = null,
        )
        assertTrue("Chapter One h1", html.contains("<h1>Chapter One</h1>"))
        assertTrue("Chapter Two h1", html.contains("<h1>Chapter Two</h1>"))
    }

    @Test
    fun combinedHtml_hasNoReadiumAssetLink() {
        val html = buildCombinedHtml(
            factory = factory,
            chapters = listOf(ChapterElision("ch1.xhtml", "Ch", listOf(highlight("h1", "text")))),
            bookTitle = null,
            figureBytesByHref = emptyMap(),
            publisherFontFaceCss = "",
            bookBodyFontFamily = null,
        )
        assertFalse("no readium_assets href", html.contains("readium_assets"))
    }

    @Test
    fun combinedHtml_tapSpansHidden() {
        val html = buildCombinedHtml(
            factory = factory,
            chapters = listOf(ChapterElision("ch1.xhtml", "Ch", listOf(highlight("h1", "text")))),
            bookTitle = null,
            figureBytesByHref = emptyMap(),
            publisherFontFaceCss = "",
            bookBodyFontFamily = null,
        )
        // The tap-dispatch span class must be hidden in the exported PDF.
        assertTrue("tap class hidden", html.contains(".$ACCENT_BAR_TAP_CLASS") && html.contains("display:none"))
    }

    @Test
    fun combinedHtml_includesPublisherFontFaceWhenNonBlank() {
        val fontCss = "@font-face { font-family: TestFont; src: url(data:font/woff2;base64,abc); }"
        val html = buildCombinedHtml(
            factory = factory,
            chapters = listOf(ChapterElision("ch1.xhtml", "Ch", listOf(highlight("h1", "text")))),
            bookTitle = null,
            figureBytesByHref = emptyMap(),
            publisherFontFaceCss = fontCss,
            bookBodyFontFamily = null,
        )
        assertTrue("publisher font-face present", html.contains("TestFont"))
    }

    @Test
    fun combinedHtml_skipsChaptersWithNoHighlights() {
        val html = buildCombinedHtml(
            factory = factory,
            chapters = listOf(
                ChapterElision("ch1.xhtml", "Has Highlights", listOf(highlight("h1", "text"))),
                ChapterElision("ch2.xhtml", "Empty Chapter", emptyList()),
            ),
            bookTitle = null,
            figureBytesByHref = emptyMap(),
            publisherFontFaceCss = "",
            bookBodyFontFamily = null,
        )
        assertTrue("non-empty chapter present", html.contains("<h1>Has Highlights</h1>"))
        assertFalse("empty chapter absent", html.contains("<h1>Empty Chapter</h1>"))
    }
}
