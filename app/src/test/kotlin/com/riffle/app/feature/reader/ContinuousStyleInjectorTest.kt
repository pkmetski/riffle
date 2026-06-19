package com.riffle.app.feature.reader

import com.riffle.core.domain.FormattingPreferences
import com.riffle.core.domain.ReaderFontFamily
import com.riffle.core.domain.ReaderTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Continuous mode injects the SAME ReadiumCSS Scroll/Paginated mode uses, driven by the
 * `--USER__*` settings attribute on `<html>`. These tests pin the attribute that
 * [FormattingPreferencesMapper] -> Readium's UserProperties would produce for the same prefs, plus
 * the stylesheet-link injection structure.
 */
class ContinuousStyleInjectorTest {

    private fun attr(prefs: FormattingPreferences): String =
        ContinuousStyleInjector.buildHtmlStyleAttr(prefs)

    // ── html style attribute (--USER__*) ────────────────────────────────────────

    @Test
    fun `always scrolled and advanced (publisher styles off)`() {
        val s = attr(FormattingPreferences())
        assertTrue(s.contains("--USER__view: readium-scroll-on !important;"))
        assertTrue(s.contains("--USER__advancedSettings: readium-advanced-on !important;"))
    }

    @Test
    fun `default font size is 100 percent`() {
        assertTrue(attr(FormattingPreferences()).contains("--USER__fontSize: 100% !important;"))
    }

    @Test
    fun `non-default font size in percent`() {
        assertTrue(attr(FormattingPreferences(fontSize = 1.5f)).contains("--USER__fontSize: 150% !important;"))
    }

    @Test
    fun `default page margins emitted as readium double`() {
        assertTrue(attr(FormattingPreferences()).contains("--USER__pageMargins: 1.0 !important;"))
    }

    @Test
    fun `justify on maps to textAlign justify`() {
        val s = attr(FormattingPreferences(justifyText = true))
        assertTrue(s.contains("--USER__textAlign: justify !important;"))
    }

    @Test
    fun `justify off omits textAlign — publisher alignment preserved (mirrors Readium null contract)`() {
        val s = attr(FormattingPreferences(justifyText = false))
        assertFalse("--USER__textAlign must not be set when justifyText=false", s.contains("--USER__textAlign"))
    }

    @Test
    fun `default line height is omitted (null-gated like the mapper)`() {
        assertFalse(attr(FormattingPreferences()).contains("--USER__lineHeight"))
    }

    @Test
    fun `non-default line height emitted`() {
        // Float→Double widening (1.6f.toDouble()) yields 1.6000000238…, exactly as Readium's
        // Scroll mode emits it (same mapper path), so match on the 1.6 prefix.
        assertTrue(attr(FormattingPreferences(lineSpacing = 1.6f)).contains("--USER__lineHeight: 1.6"))
    }

    // ── font family ──────────────────────────────────────────────────────────────

    @Test
    fun `Serif (default) emits no font override — publisher font, matching Readium null mapping`() {
        val s = attr(FormattingPreferences(fontFamily = ReaderFontFamily.Serif))
        assertFalse("no font override", s.contains("--USER__fontOverride"))
        assertFalse("no font family", s.contains("--USER__fontFamily"))
    }

    @Test
    fun `SansSerif sets fontOverride and quoted family`() {
        val s = attr(FormattingPreferences(fontFamily = ReaderFontFamily.SansSerif))
        assertTrue(s.contains("--USER__fontOverride: readium-font-on !important;"))
        assertTrue(s.contains("--USER__fontFamily: \"sans-serif\" !important;"))
    }

    @Test
    fun `Monospace sets quoted family`() {
        assertTrue(attr(FormattingPreferences(fontFamily = ReaderFontFamily.Monospace))
            .contains("--USER__fontFamily: \"monospace\" !important;"))
    }

    @Test
    fun `Literata sets quoted family`() {
        assertTrue(attr(FormattingPreferences(fontFamily = ReaderFontFamily.Literata))
            .contains("--USER__fontFamily: \"Literata\" !important;"))
    }

    // ── theme / appearance ─────────────────────────────────────────────────────────

    @Test
    fun `Light theme — no appearance flag`() {
        assertFalse(attr(FormattingPreferences(theme = ReaderTheme.Light)).contains("--USER__appearance"))
    }

    @Test
    fun `Dark theme — night appearance, no explicit text colour`() {
        val s = attr(FormattingPreferences(theme = ReaderTheme.Dark))
        assertTrue(s.contains("--USER__appearance: readium-night-on !important;"))
        assertFalse(s.contains("--USER__textColor"))
    }

    @Test
    fun `DarkDim theme — night appearance plus dim grey text colour`() {
        val s = attr(FormattingPreferences(theme = ReaderTheme.DarkDim))
        assertTrue(s.contains("--USER__appearance: readium-night-on !important;"))
        assertTrue(s.contains("--USER__textColor: #AAAAAA !important;"))
    }

    @Test
    fun `Sepia theme — sepia appearance`() {
        assertTrue(attr(FormattingPreferences(theme = ReaderTheme.Sepia))
            .contains("--USER__appearance: readium-sepia-on !important;"))
    }

    // ── HTML injection structure ────────────────────────────────────────────────

    private val sampleHtml =
        "<html xmlns=\"http://www.w3.org/1999/xhtml\"><head><title>t</title></head><body><p>hi</p></body></html>"

    @Test
    fun `injectInto adds before and after ReadiumCSS links`() {
        val out = ContinuousStyleInjector.injectInto(sampleHtml, FormattingPreferences())
        assertTrue(out.contains("readium/readium-css/ReadiumCSS-before.css"))
        assertTrue(out.contains("readium/readium-css/ReadiumCSS-after.css"))
    }

    @Test
    fun `injectInto puts before-css after head open and after-css before head close`() {
        val out = ContinuousStyleInjector.injectInto(sampleHtml, FormattingPreferences())
        val before = out.indexOf("ReadiumCSS-before.css")
        val after = out.indexOf("ReadiumCSS-after.css")
        val headClose = out.indexOf("</head>")
        assertTrue("before precedes after", before < after)
        assertTrue("after precedes </head>", after < headClose)
    }

    @Test
    fun `injectInto adds the user style attribute onto html`() {
        val out = ContinuousStyleInjector.injectInto(sampleHtml, FormattingPreferences(fontSize = 1.5f))
        assertTrue(out.contains("<html"))
        assertTrue(out.contains("style="))
        assertTrue(out.contains("--USER__fontSize: 150% !important;"))
    }

    @Test
    fun `injectInto includes bundled font-face declarations`() {
        val out = ContinuousStyleInjector.injectInto(sampleHtml, FormattingPreferences())
        assertTrue(out.contains("@font-face"))
        assertTrue(out.contains("Literata-Regular.ttf"))
        assertTrue(out.contains("OpenDyslexic-Regular.otf"))
    }

    @Test
    fun `injectInto adds default-css only when chapter has no author styles`() {
        val withStyles = "<html><head><style>p{}</style></head><body></body></html>"
        val without = "<html><head><title>t</title></head><body></body></html>"
        assertFalse(ContinuousStyleInjector.injectInto(withStyles, FormattingPreferences())
            .contains("ReadiumCSS-default.css"))
        assertTrue(ContinuousStyleInjector.injectInto(without, FormattingPreferences())
            .contains("ReadiumCSS-default.css"))
    }

    // ── live preference-change JS ───────────────────────────────────────────────

    @Test
    fun `buildStyleInjectionJs sets the documentElement style attribute`() {
        val js = ContinuousStyleInjector.buildStyleInjectionJs(FormattingPreferences(fontSize = 1.5f))
        assertTrue(js.contains("document.documentElement.setAttribute('style'"))
        assertTrue(js.contains("--USER__fontSize: 150% !important;"))
        assertFalse("--USER__textAlign absent when justifyText=false", js.contains("--USER__textAlign"))
    }

    // ── highlight helpers (unchanged) ───────────────────────────────────────────

    @Test
    fun `highlightTextJs escapes single quotes in text`() {
        val js = ContinuousStyleInjector.highlightTextJs("it's a test", "rgba(56,189,248,0.30)")
        assertTrue(js.contains("it\\'s a test"))
        assertFalse(js.contains("it's a test"))
    }

    @Test
    fun `highlightTextJs escapes newlines in text`() {
        val js = ContinuousStyleInjector.highlightTextJs("line one\nline two", "rgba(56,189,248,0.30)")
        assertTrue(js.contains("\\n"))
        assertFalse(js.contains("line one\nline two"))
    }

    // ── CLEAR_ANNOTATION_HIGHLIGHTS_JS ─────────────────────────────────────────

    @Test
    fun `CLEAR removes note-glyph spans before unwrapping marks`() {
        val js = ContinuousStyleInjector.CLEAR_ANNOTATION_HIGHLIGHTS_JS
        val glyphIdx = js.indexOf("data-riffle-note-glyph")
        val markIdx = js.indexOf("data-riffle-ann")
        assertTrue("glyph selector present", glyphIdx >= 0)
        assertTrue("mark selector present", markIdx >= 0)
        assertTrue("glyphs removed before marks (child must go before parent)", glyphIdx < markIdx)
    }

    @Test
    fun `CLEAR uses outerHTML replacement to unwrap marks`() {
        assertTrue(ContinuousStyleInjector.CLEAR_ANNOTATION_HIGHLIGHTS_JS.contains("outerHTML = m.innerHTML"))
    }

    // ── applyAnnotationHighlightsJs — JSON payload ───────────────────────────

    private fun applyJs(vararg annotations: AnnotationHighlight) =
        ContinuousStyleInjector.applyAnnotationHighlightsJs(annotations.toList())

    @Test
    fun `empty list produces empty JSON array`() {
        val js = ContinuousStyleInjector.applyAnnotationHighlightsJs(emptyList())
        assertTrue(js.contains("([])"))
    }

    @Test
    fun `annotation without note emits n colon 0`() {
        val js = applyJs(AnnotationHighlight("ann1", "hello", "#ff0000", hasNote = false))
        assertTrue(js.contains("n:0"))
        assertFalse(js.contains("n:1"))
    }

    @Test
    fun `annotation with note emits n colon 1`() {
        val js = applyJs(AnnotationHighlight("ann1", "hello", "#ff0000", hasNote = true))
        assertTrue(js.contains("n:1"))
    }

    @Test
    fun `each annotation is emitted with id text color and note fields`() {
        val js = applyJs(AnnotationHighlight("myId", "my text", "#abc123", hasNote = false))
        assertTrue("id field", js.contains("id:'myId'"))
        assertTrue("text field", js.contains("t:'my text'"))
        assertTrue("color field", js.contains("c:'#abc123'"))
        assertTrue("note field", js.contains("n:0"))
    }

    @Test
    fun `multiple annotations are comma-separated in JSON`() {
        val js = applyJs(
            AnnotationHighlight("a1", "first", "#111", hasNote = false),
            AnnotationHighlight("a2", "second", "#222", hasNote = true),
        )
        assertTrue("first id present", js.contains("id:'a1'"))
        assertTrue("second id present", js.contains("id:'a2'"))
        assertTrue("first text present", js.contains("t:'first'"))
        assertTrue("second text present", js.contains("t:'second'"))
        assertTrue("first has n:0", js.contains("n:0"))
        assertTrue("second has n:1", js.contains("n:1"))
    }

    @Test
    fun `single quotes in annotation id are escaped`() {
        val js = applyJs(AnnotationHighlight("it's-id", "text", "#000"))
        assertTrue(js.contains("id:'it\\'s-id'"))
        assertFalse("raw single quote must not appear in id", js.contains("id:'it's-id'"))
    }

    @Test
    fun `single quotes in annotation text are escaped`() {
        val js = applyJs(AnnotationHighlight("ann1", "don't stop", "#000"))
        assertTrue(js.contains("t:'don\\'t stop'"))
        assertFalse("raw single quote must not appear in text", js.contains("t:'don't stop'"))
    }

    @Test
    fun `backslashes in text are escaped`() {
        val js = applyJs(AnnotationHighlight("ann1", "back\\slash", "#000"))
        assertTrue(js.contains("back\\\\slash"))
    }

    @Test
    fun `newlines in text are escaped as backslash-n`() {
        val js = applyJs(AnnotationHighlight("ann1", "line1\nline2", "#000"))
        assertTrue(js.contains("\\n"))
        assertFalse("literal newline must not appear in JS string", js.contains("line1\nline2"))
    }

    @Test
    fun `carriage returns in text are escaped as backslash-r`() {
        val js = applyJs(AnnotationHighlight("ann1", "cr\rtext", "#000"))
        assertTrue(js.contains("\\r"))
    }

    // ── applyAnnotationHighlightsJs — DOM logic ──────────────────────────────

    @Test
    fun `JS sets data-riffle-ann attribute on injected mark`() {
        val js = applyJs(AnnotationHighlight("ann42", "some text", "#ff0"))
        assertTrue(js.contains("data-riffle-ann"))
    }

    @Test
    fun `JS sets data-riffle-note-glyph attribute on injected glyph`() {
        val js = applyJs(AnnotationHighlight("ann42", "text", "#ff0", hasNote = true))
        assertTrue(js.contains("data-riffle-note-glyph"))
    }

    @Test
    fun `existing mark is updated in-place via style-background — no window-find`() {
        val js = applyJs(AnnotationHighlight("ann1", "text", "#abc"))
        // The in-place update path sets style.background on the existing mark.
        assertTrue(js.contains("existing.style.background"))
        // And it calls window.find() only for NEW annotations (after the in-place branch returns).
        assertTrue(js.contains("window.find("))
    }

    @Test
    fun `stale marks are removed when their id is no longer in the annotation list`() {
        val js = applyJs(AnnotationHighlight("ann1", "text", "#abc"))
        // validIds is the guard that removes marks whose id is absent from the current list.
        assertTrue(js.contains("validIds"))
        assertTrue(js.contains("outerHTML = m.innerHTML"))
    }

    @Test
    fun `glyph is added when annotation gains a note`() {
        val js = applyJs(AnnotationHighlight("ann1", "text", "#abc", hasNote = true))
        // The branch: if ann.n && !eg → makeGlyph
        assertTrue(js.contains("ann.n && !eg"))
        assertTrue(js.contains("makeGlyph"))
    }

    @Test
    fun `glyph is removed when annotation loses its note`() {
        val js = applyJs(AnnotationHighlight("ann1", "text", "#abc", hasNote = false))
        // The branch: !ann.n && eg → eg.remove()
        assertTrue(js.contains("!ann.n && eg"))
        assertTrue(js.contains("eg.remove()"))
    }

    @Test
    fun `glyph is positioned 28px to the left of the mark edge not the block edge`() {
        val js = applyJs(AnnotationHighlight("ann1", "text", "#abc", hasNote = true))
        // relLeft = markRect.left - blockRect.left - 28 (28px left of mark, not block left)
        assertTrue(js.contains("markRect.left - blockRect.left - 28"))
    }

    @Test
    fun `glyph click calls onAnnotationNoteTap bridge method`() {
        val js = applyJs(AnnotationHighlight("ann1", "text", "#abc", hasNote = true))
        assertTrue(js.contains("onAnnotationNoteTap"))
    }

    @Test
    fun `mark click calls onAnnotationTap bridge method`() {
        val js = applyJs(AnnotationHighlight("ann1", "text", "#abc"))
        assertTrue(js.contains("onAnnotationTap"))
    }

    @Test
    fun `selection is cleared after applying annotations`() {
        val js = applyJs(AnnotationHighlight("ann1", "text", "#abc"))
        assertTrue(js.contains("removeAllRanges"))
    }

    // ── applyAnnotationHighlightsJs — note-glyph SVG ────────────────────────

    @Test
    fun `NoteAlt SVG data-URI is embedded in the JS`() {
        val js = applyJs(AnnotationHighlight("ann1", "text", "#abc", hasNote = true))
        // The SVG URI contains the document-page path.
        assertTrue(js.contains("data:image/svg+xml,"))
        // webkit-mask-image is used to render the icon (inherits currentColor).
        assertTrue(js.contains("-webkit-mask-image"))
    }

    @Test
    fun `single quotes in SVG URI are escaped for JS embedding`() {
        // NOTE_GLYPH_SVG_DATA_URI contains literal single quotes (e.g. xmlns='...').
        // The injector must escape them so the SVG_URI string assignment is syntactically valid JS.
        assertTrue("SVG constant has single quotes to escape", NOTE_GLYPH_SVG_DATA_URI.contains("'"))
        val js = applyJs(AnnotationHighlight("ann1", "text", "#abc", hasNote = true))
        // After escaping, xmlns=' becomes xmlns=\' — the raw xmlns=' must not appear in the JS.
        assertFalse("raw xmlns=' must be escaped in the JS", js.contains("xmlns='"))
        // The escaped form IS present.
        assertTrue("escaped xmlns=\\' must appear in the JS", js.contains("xmlns=\\'"))
    }

    // ── AnnotationHighlight data class ───────────────────────────────────────

    @Test
    fun `AnnotationHighlight hasNote defaults to false`() {
        val ann = AnnotationHighlight("id", "text", "#000")
        assertFalse(ann.hasNote)
    }

    @Test
    fun `AnnotationHighlight equality and copy`() {
        val ann = AnnotationHighlight("id", "text", "#000", hasNote = true)
        assertEquals(ann, AnnotationHighlight("id", "text", "#000", hasNote = true))
        assertFalse(ann == ann.copy(hasNote = false))
    }
}
