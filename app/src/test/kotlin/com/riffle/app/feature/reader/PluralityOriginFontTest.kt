package com.riffle.app.feature.reader

import com.riffle.app.feature.reader.highlights.ChapterElision
import com.riffle.app.feature.reader.highlights.FALLBACK_ORIGIN_FONT_FAMILY
import com.riffle.core.database.AnnotationEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Pins the plurality vote used to derive the elided view's fallback `bookBodyFontFamily`
 * ([EpubReaderViewModel.loadHighlightsPublication] → [HighlightsPublicationFactory]).
 *
 * Regression: `elided-view-serif-font-regression`. When every annotation on a book was stamped
 * with the [FALLBACK_ORIGIN_FONT_FAMILY] sentinel (bookmarks toggled without a live selection,
 * caption highlights, or the SelectionFontStash race), the plurality used to return "serif",
 * which the factory then forced onto `<body>, h1, ..., aside, figcaption` via a
 * `font-family: serif !important` rule — the chapter title and every excerpt rendered in browser
 * default serif regardless of the book's real face or the user's formatting preferences. The
 * sentinel is a "no captured value" marker, not a rendering directive; plurality must skip it.
 */
class PluralityOriginFontTest {

    @Test
    fun pluralityReturnsRealFontWhenPresent() {
        val chapters = listOf(
            chapter(
                "ch0",
                hl("h1", originFontFamily = "Georgia, serif"),
                hl("h2", originFontFamily = "Georgia, serif"),
                hl("h3", originFontFamily = "\"Fira Sans\", sans-serif"),
            ),
        )
        assertEquals("Georgia, serif", pluralityOriginFont(chapters))
    }

    @Test
    fun pluralityIgnoresSentinelWhenARealFontIsPresent() {
        val chapters = listOf(
            chapter(
                "ch0",
                hl("h1", originFontFamily = FALLBACK_ORIGIN_FONT_FAMILY),
                hl("h2", originFontFamily = FALLBACK_ORIGIN_FONT_FAMILY),
                hl("h3", originFontFamily = FALLBACK_ORIGIN_FONT_FAMILY),
                hl("h4", originFontFamily = "Merriweather, serif"),
            ),
        )
        assertEquals(
            "sentinel-stamped rows outnumber the real one, but plurality must still pick the real font",
            "Merriweather, serif",
            pluralityOriginFont(chapters),
        )
    }

    @Test
    fun pluralityReturnsNullWhenOnlySentinelStampedRowsExist() {
        val chapters = listOf(
            chapter(
                "ch0",
                hl("h1", originFontFamily = FALLBACK_ORIGIN_FONT_FAMILY),
                hl("h2", originFontFamily = FALLBACK_ORIGIN_FONT_FAMILY),
            ),
        )
        assertNull(
            "all-sentinel input must yield null so the caller falls through to the WebView probe / ReadiumCSS default",
            pluralityOriginFont(chapters),
        )
    }

    @Test
    fun pluralityReturnsNullWhenAllFontsAreBlankOrNull() {
        val chapters = listOf(
            chapter(
                "ch0",
                hl("h1", originFontFamily = null),
                hl("h2", originFontFamily = ""),
                hl("h3", originFontFamily = "   "),
            ),
        )
        assertNull(pluralityOriginFont(chapters))
    }

    private fun chapter(href: String, vararg highlights: AnnotationEntity) =
        ChapterElision(href = href, title = "T", highlights = highlights.toList())

    private fun hl(id: String, originFontFamily: String?): AnnotationEntity = AnnotationEntity(
        id = id,
        sourceId = "S1",
        itemId = "B1",
        type = AnnotationEntity.TYPE_HIGHLIGHT,
        cfi = "cfi",
        color = "yellow",
        note = null,
        textSnippet = "snippet",
        textBefore = "",
        textAfter = "",
        chapterHref = "ch0.xhtml",
        spineIndex = 0,
        progression = 0.0,
        bookmarkTitle = "",
        createdAt = 0L,
        updatedAt = 0L,
        originDeviceId = "d",
        lastModifiedByDeviceId = "d",
        deleted = false,
        originFontFamily = originFontFamily,
    )
}
