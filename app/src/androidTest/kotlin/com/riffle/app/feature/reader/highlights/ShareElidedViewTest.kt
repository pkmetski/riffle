package com.riffle.app.feature.reader.highlights

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.riffle.core.database.AnnotationEntity
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Verifies HTML assembly for the share-elided-view (PDF export) path.
 *
 * [buildCombinedHtml] is an `internal` top-level function exercised on-device here to validate the
 * full assembly pipeline with a real [android.content.Context]-free path (it takes no Context).
 * A full UI/PDF-render harness test (tapping Share, waiting for the print adapter, confirming file
 * size) requires a device-level PrintManager round-trip that can't be driven without a running
 * Activity; this test covers the HTML correctness that is a prerequisite for any valid PDF output.
 *
 * Assertions that would flip red if the fix were reverted:
 *  - Chapter titles appear as `<h1>` elements in the combined output.
 *  - The `<!DOCTYPE html>` + `<html>` wrapper is present (regression: missing wrapping would
 *    cause WebView to fail to render the PDF document).
 *  - Readium asset URLs are absent (the PDF WebView has no Readium WebViewServer; a link to
 *    `readium_assets` would 404 silently and break fonts/styling in the exported PDF).
 *  - Tap-dispatch spans are hidden via `display:none` (they have no function in a static PDF and
 *    must not produce invisible-but-clickable dead zones in the PDF viewer).
 *  - Chapters with no highlights are silently skipped (an empty `<h1>` with no body would add a
 *    spurious blank page to the exported PDF).
 */
@RunWith(AndroidJUnit4::class)
class ShareElidedViewTest {

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
    fun buildCombinedHtml_producesWellFormedDocument() {
        val html = buildCombinedHtml(
            factory = factory,
            chapters = listOf(
                ChapterElision("ch1.xhtml", "First Chapter", listOf(highlight("h1", "Some text"))),
            ),
            bookTitle = "Test Book",
            figureBytesByHref = emptyMap(),
            publisherFontFaceCss = "",
            bookBodyFontFamily = null,
        )
        assertTrue("non-empty output", html.isNotBlank())
        assertTrue("has DOCTYPE", html.startsWith("<!DOCTYPE html>"))
        assertTrue("has opening html tag", html.contains("<html>"))
        assertTrue("has closing html tag", html.contains("</html>"))
        assertTrue("has body element", html.contains("<body>"))
        assertTrue("has book title in <title>", html.contains("<title>Test Book</title>"))
    }

    @Test
    fun buildCombinedHtml_containsAllChapterTitlesAsH1() {
        val html = buildCombinedHtml(
            factory = factory,
            chapters = listOf(
                ChapterElision("ch1.xhtml", "Chapter One", listOf(highlight("h1", "text one"))),
                ChapterElision("ch2.xhtml", "Chapter Two", listOf(highlight("h2", "text two"))),
            ),
            bookTitle = "My Book",
            figureBytesByHref = emptyMap(),
            publisherFontFaceCss = "",
            bookBodyFontFamily = null,
        )
        assertTrue("Chapter One h1 present", html.contains("<h1>Chapter One</h1>"))
        assertTrue("Chapter Two h1 present", html.contains("<h1>Chapter Two</h1>"))
    }

    @Test
    fun buildCombinedHtml_hasNoReadiumAssetLinks() {
        val html = buildCombinedHtml(
            factory = factory,
            chapters = listOf(
                ChapterElision("ch1.xhtml", "Ch", listOf(highlight("h1", "text"))),
            ),
            bookTitle = null,
            figureBytesByHref = emptyMap(),
            publisherFontFaceCss = "",
            bookBodyFontFamily = null,
        )
        // The PDF WebView has no Readium WebViewServer — any readium_assets link would 404.
        assertFalse("no readium_assets href in PDF output", html.contains("readium_assets"))
    }

    @Test
    fun buildCombinedHtml_tapSpansAreHiddenInPdfOutput() {
        val html = buildCombinedHtml(
            factory = factory,
            chapters = listOf(
                ChapterElision("ch1.xhtml", "Ch", listOf(highlight("h1", "text"))),
            ),
            bookTitle = null,
            figureBytesByHref = emptyMap(),
            publisherFontFaceCss = "",
            bookBodyFontFamily = null,
        )
        // Tap dispatch spans must be hidden — they have no meaning in a static PDF.
        assertTrue(
            "tap class is declared as display:none",
            html.contains(".$ACCENT_BAR_TAP_CLASS") && html.contains("display:none"),
        )
    }

    @Test
    fun buildCombinedHtml_skipsChaptersWithNoHighlights() {
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
        assertTrue("non-empty chapter is present", html.contains("<h1>Has Highlights</h1>"))
        assertFalse("empty chapter is absent from PDF output", html.contains("<h1>Empty Chapter</h1>"))
    }

    @Test
    fun buildCombinedHtml_includesHighlightTextContent() {
        val html = buildCombinedHtml(
            factory = factory,
            chapters = listOf(
                ChapterElision(
                    "ch1.xhtml", "My Chapter",
                    listOf(highlight("h1", "The annotated text snippet")),
                ),
            ),
            bookTitle = null,
            figureBytesByHref = emptyMap(),
            publisherFontFaceCss = "",
            bookBodyFontFamily = null,
        )
        assertTrue("highlight snippet text present in output", html.contains("The annotated text snippet"))
    }
}
