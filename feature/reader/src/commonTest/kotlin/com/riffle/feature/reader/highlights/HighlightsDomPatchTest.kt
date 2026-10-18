package com.riffle.feature.reader.highlights

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HighlightsDomPatchTest {

    @Test
    fun `Recolor JS targets the accent bar and any adjacent note`() {
        val js = HighlightsDomPatch.Recolor(
            annotationId = "abc-123",
            accentCssRgba = "rgba(255,193,0,1)",
            barWidthPx = "4px",
        ).applyJs()
        assertTrue(js.contains("\"abc-123\""), js)
        assertTrue(js.contains("rgba(255,193,0,1)"), js)
        assertTrue(js.contains("data-ann-id="), "recolor JS must find nodes by data-ann-id")
        assertTrue(
            js.contains("closest('p, figure')"),
            "recolor JS must fall through to the paragraph OR figure via closest('p, figure')",
        )
        assertTrue(
            js.contains("FIGURE"),
            "recolor JS must treat a <figure> host as directly-target-able (fix 2026-07-10)",
        )
        assertTrue(js.contains("border-left-color"), "recolor JS must set border-left-color")
        assertTrue(js.contains("border-left-width"), "recolor JS must set border-left-width on non-aside hosts")
        assertTrue(js.contains("tagName !== 'ASIDE'"), "recolor JS must not change aside border-left-width")
        assertTrue(
            js.contains("'important'") || js.contains("!important"),
            "recolor JS must use setProperty with 'important' to defeat ReadiumCSS theming",
        )
        assertTrue(js.contains("\"4px\""), "colored bar uses 4px width")
    }

    @Test
    fun recolorJsUsesTwoPxWidthForEmphasisOnlyHighlights() {
        val js = HighlightsDomPatch.Recolor(
            annotationId = "h-1",
            accentCssRgba = EMPHASIS_ONLY_BAR_COLOR,
            barWidthPx = "2px",
        ).applyJs()
        assertTrue(js.contains("\"2px\""), "emphasis-only bar uses 2px width")
        assertTrue(js.contains(EMPHASIS_ONLY_BAR_COLOR), "emphasis-only color present")
    }

    @Test
    fun `SetNote with non-null text creates an aside or updates textContent`() {
        val js = HighlightsDomPatch.SetNote(
            annotationId = "id-9",
            accentCssRgba = "rgba(0,128,255,1)",
            noteText = "Hello",
        ).applyJs()
        assertTrue(js.contains("\"Hello\""), js)
        assertTrue(js.contains("createElement('aside')"), "must create an ASIDE element when missing")
        assertTrue(js.contains("'riffle-note'"), "must set the class and data-ann-id on new asides")
        assertTrue(
            js.contains("setAttribute('role', 'note')") &&
                js.contains("setAttribute('aria-label', '$ELIDED_NOTE_ARIA_LABEL')"),
            "live-created notes must expose the same note semantics as initial chapter HTML",
        )
        assertTrue(
            js.contains("aside.riffle-note[data-ann-id="),
            "must look up the existing aside by its own data-ann-id (adjacency is unreliable through Readium's HTMLInjector)",
        )
        assertTrue(js.contains("textContent"), "must update textContent (not innerHTML — avoid injection)")
        assertTrue(
            js.contains("i = nodes.length - 1"),
            "SetNote must scan nodes from the END to place the aside after the last chunk",
        )
    }

    @Test
    fun `SetNote with null text removes the aside`() {
        val js = HighlightsDomPatch.SetNote(
            annotationId = "id-9",
            accentCssRgba = "rgba(0,0,0,1)",
            noteText = null,
        ).applyJs()
        assertTrue(js.contains("aside.remove()"), "null note must branch to remove()")
        assertTrue(js.contains("note === null"), "null-note path must be gated on `note === null`")
    }

    @Test
    fun `Remove JS deletes the paragraph and any adjacent note`() {
        val js = HighlightsDomPatch.Remove("id-42").applyJs()
        assertTrue(js.contains("\"id-42\""))
        assertTrue(js.contains("p.remove()"), "Remove JS must delete the containing paragraph")
        assertTrue(js.contains("aside.remove()"), "Remove JS must also drop the aside with the same data-ann-id")
        assertTrue(
            js.contains("aside.riffle-note[data-ann-id="),
            "Remove JS must look up the aside directly by data-ann-id",
        )
        assertTrue(js.contains("closest('p')"))
        assertTrue(
            js.contains("figure.riffle-fig[data-ann-id="),
            "Remove JS must also drop any figure.riffle-fig block carrying this id (fix 2026-07-10)",
        )
        assertTrue(
            js.contains("querySelectorAll('span.riffle-hl["),
            "Remove JS must iterate span.riffle-hl matches (querySelectorAll) to catch every chunk",
        )
    }

    @Test
    fun `SetEmphasis with non-empty styles sets cssText on riffle-hl spans`() {
        val js = HighlightsDomPatch.SetEmphasis(
            annotationId = "id-bold",
            emphasisCss = "font-weight:bold !important;",
        ).applyJs()
        assertTrue(js.contains("\"id-bold\""), js)
        assertTrue(js.contains("span.riffle-hl[data-ann-id="), "must target span.riffle-hl by data-ann-id")
        assertTrue(js.contains("style.cssText"), "must set style.cssText")
        assertTrue(js.contains("font-weight:bold"), "must contain the provided css value")
    }

    @Test
    fun `SetEmphasis with empty css clears inline style on riffle-hl spans`() {
        val js = HighlightsDomPatch.SetEmphasis(
            annotationId = "id-clear",
            emphasisCss = "",
        ).applyJs()
        assertTrue(js.contains("\"id-clear\""), js)
        assertTrue(js.contains("style.cssText"), "must set style.cssText even for empty css")
    }

    @Test
    fun `buildEmphasisInlineCss returns empty string for empty styles`() {
        assertEquals("", buildEmphasisInlineCss(emptySet()))
    }

    @Test
    fun `buildEmphasisInlineCss encodes bold`() {
        val css = buildEmphasisInlineCss(setOf(com.riffle.core.models.EmphasisStyle.BOLD))
        assertTrue(css.contains("font-weight:bold"))
        assertTrue(css.contains("!important"))
    }

    @Test
    fun `buildEmphasisInlineCss encodes italic`() {
        val css = buildEmphasisInlineCss(setOf(com.riffle.core.models.EmphasisStyle.ITALIC))
        assertTrue(css.contains("font-style:italic"))
        assertTrue(css.contains("!important"))
    }

    @Test
    fun `buildEmphasisInlineCss encodes underline and strike as text-decoration`() {
        val css = buildEmphasisInlineCss(
            setOf(
                com.riffle.core.models.EmphasisStyle.UNDERLINE,
                com.riffle.core.models.EmphasisStyle.STRIKE,
            ),
        )
        assertTrue(css.contains("text-decoration:"))
        assertTrue(css.contains("underline"))
        assertTrue(css.contains("line-through"))
        assertTrue(css.contains("!important"))
    }

    @Test
    fun `buildEmphasisInlineCss combines all four styles`() {
        val css = buildEmphasisInlineCss(
            setOf(
                com.riffle.core.models.EmphasisStyle.BOLD,
                com.riffle.core.models.EmphasisStyle.ITALIC,
                com.riffle.core.models.EmphasisStyle.UNDERLINE,
                com.riffle.core.models.EmphasisStyle.STRIKE,
            ),
        )
        assertTrue(css.contains("font-weight:bold"))
        assertTrue(css.contains("font-style:italic"))
        assertTrue(css.contains("underline"))
        assertTrue(css.contains("line-through"))
    }

    @Test
    fun `annotationId is JS-escaped so a quote in the id cannot break out`() {
        val js = HighlightsDomPatch.Recolor(
            annotationId = "id\"; document.body.remove(); //",
            accentCssRgba = "red",
        ).applyJs()
        assertTrue(js.contains("id\\\";"), "id containing a quote must be escaped")
        assertFalse(
            js.contains("\"id\"; document.body.remove()"),
            "injection attempt must remain inside the string literal",
        )
    }
}
