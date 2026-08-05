package com.riffle.app.feature.reader

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Real-WebView regression coverage for note glyphs at the minimum reader margin. */
@RunWith(AndroidJUnit4::class)
class NoteGlyphMarginWebViewTest {

    @Test
    fun paginatedGlyphKeepsReadableViewportInsetWhenSelectionStartsNearPageEdge() {
        val stylesheet = noteGlyphTemplate().stylesheet.orEmpty()
        val html = """
            <!doctype html>
            <html>
              <head><style>$stylesheet</style></head>
              <body style="margin:0">
                <div id="selection" style="position:absolute;left:8px;top:40px;width:160px;height:24px">
                  $NOTE_GLYPH_ELEMENT_HTML
                </div>
                <div id="next-selection" style="position:absolute;left:408px;top:40px;width:160px;height:24px">
                  $NOTE_GLYPH_ELEMENT_HTML
                </div>
              </body>
            </html>
        """.trimIndent()

        withSizedWebViewFixture(html, widthPx = 400, heightPx = 600) { webView ->
            webView.evalSync(noteGlyphViewportClampAfterApplyJs())
            val lefts = webView.evalSync(
                "JSON.stringify(Array.prototype.map.call(" +
                    "document.querySelectorAll('.$NOTE_GLYPH_ICON_CLASS')," +
                    "function(e){return e.getBoundingClientRect().left;}))"
            ).trim('"').removePrefix("[").removeSuffix("]").split(',').map(String::toDouble)

            assertTrue(
                "visible-spread glyph must stay at least ${NOTE_GLYPH_VIEWPORT_INSET_PX}px inside; lefts=$lefts",
                lefts[0] >= NOTE_GLYPH_VIEWPORT_INSET_PX,
            )
            assertTrue(
                "adjacent-spread glyph must stay on its own spread; lefts=$lefts",
                lefts[1] >= 400 + NOTE_GLYPH_VIEWPORT_INSET_PX,
            )
        }
    }

    @Test
    fun continuousGlyphKeepsReadableViewportInsetWhenTextStartsNearPageEdge() {
        val html = """
            <!doctype html>
            <html>
              <head><style>html,body,p{margin:0} p{padding-left:8px}</style></head>
              <body><p>Narrow margin note glyph fixture text.</p></body>
            </html>
        """.trimIndent()

        withSizedWebViewFixture(html, widthPx = 400, heightPx = 600) { webView ->
            val annotationId = "narrow-note"
            val annotation = AnnotationHighlight(
                id = annotationId,
                text = "Narrow margin",
                cssColor = "rgba(33,150,243,0.45)",
                hasNote = true,
                before = "",
                after = " note",
            )
            webView.evalSync(ContinuousStyleInjector.applyAnnotationHighlightsJs(listOf(annotation)))
            val left = webView.evalSync(
                "document.querySelector('[data-riffle-note-glyph=\"$annotationId\"]')" +
                    ".getBoundingClientRect().left.toString()"
            ).trim('"').toDouble()

            assertTrue(
                "continuous glyph must stay at least ${NOTE_GLYPH_VIEWPORT_INSET_PX}px inside; left=$left",
                left >= NOTE_GLYPH_VIEWPORT_INSET_PX,
            )
        }
    }
}
