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
    fun `injectInto embeds typography override CSS after ReadiumCSS-after to beat its text-align inherit rule`() {
        val out = ContinuousStyleInjector.injectInto(sampleHtml, FormattingPreferences())
        val afterIdx = out.indexOf("ReadiumCSS-after.css")
        val overrideIdx = out.indexOf("riffle-typography-override")
        val headCloseIdx = out.indexOf("</head>")
        assertTrue("override style block present", overrideIdx >= 0)
        assertTrue("override after ReadiumCSS-after", overrideIdx > afterIdx)
        assertTrue("override before </head>", overrideIdx < headCloseIdx)
        // Verify the justify gate uses 3 attribute-selector repetitions (specificity 0,3,2) to
        // beat ReadiumCSS-after.css's text-align: inherit !important rule (specificity 0,2,4).
        assertTrue("3x gate attr reps present", out.contains("[style*=\"--USER__textAlign\"][style*=\"--USER__textAlign\"][style*=\"--USER__textAlign\"]"))
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

    @Test
    fun `highlightTextJs uses sentinel to preserve search position after clearing old mark`() {
        // The sentinel trick: a temporary span is inserted AFTER the existing _riffle_hl mark
        // before the outerHTML mutation, so window.find() starts from the end of the previous
        // sentence rather than from the document start (which would find the first occurrence
        // and misplace the highlight when the same word/phrase recurs earlier in the chapter).
        val js = ContinuousStyleInjector.highlightTextJs("test sentence", "rgba(56,189,248,0.30)")
        assertTrue("inserts sentinel after existing mark", js.contains("insertBefore(sentinel, existing.nextSibling)"))
        assertTrue("removes sentinel before searching", js.contains("removeChild(sentinel)"))
        assertTrue("collapses selection after sentinel", js.contains("setStartAfter(sentinel)"))
        // Sentinel must be inserted BEFORE the outerHTML mutation (not after).
        val insertIdx = js.indexOf("insertBefore(sentinel")
        val outerHtmlIdx = js.indexOf("outerHTML = existing.innerHTML")
        assertTrue("sentinel inserted before outerHTML mutation", insertIdx < outerHtmlIdx)
        // And the sentinel must be removed BEFORE window.find() is called.
        val removeIdx = js.indexOf("removeChild(sentinel)")
        val findIdx = js.indexOf("window.find(")
        assertTrue("sentinel removed before window.find()", removeIdx < findIdx)
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
    fun `each annotation is emitted with before and after context fields`() {
        val js = applyJs(
            AnnotationHighlight(
                id = "myId", text = "Куджиа", cssColor = "#abc",
                before = "Али ", after = " решил",
            )
        )
        assertTrue("before field", js.contains("b:'Али '"))
        assertTrue("after field", js.contains("a:' решил'"))
    }

    @Test
    fun `empty before and after produce empty-string b and a fields`() {
        // Legacy annotations (created before context capture) have empty before/after — the
        // emitted JS still carries the fields so the renderer's locateRange can short-circuit
        // to first-match without an undefined-vs-empty-string distinction in JS.
        val js = applyJs(AnnotationHighlight("legacy", "text", "#abc"))
        assertTrue("empty before", js.contains("b:''"))
        assertTrue("empty after", js.contains("a:''"))
    }

    @Test
    fun `single quotes in before and after are escaped`() {
        val js = applyJs(
            AnnotationHighlight(
                "id", "text", "#000",
                before = "don't ", after = " 'quoted'",
            )
        )
        assertTrue("escaped before", js.contains("b:'don\\'t '"))
        assertTrue("escaped after", js.contains("a:' \\'quoted\\''"))
        assertFalse("raw single quote must not appear in before", js.contains("b:'don't "))
    }

    @Test
    fun `newlines in before and after are escaped`() {
        val js = applyJs(
            AnnotationHighlight(
                "id", "text", "#000",
                before = "line1\nline2", after = "tail\rmore",
            )
        )
        // The b field carries the escaped sequence; literal newlines/CRs would break the JS string.
        assertFalse("literal newline must not appear", js.contains("line1\nline2"))
        assertFalse("literal CR must not appear", js.contains("tail\rmore"))
        // Spot-check both escapes survived to the output.
        assertTrue("backslash-n present", js.contains("\\n"))
        assertTrue("backslash-r present", js.contains("\\r"))
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
    fun `existing mark is updated in-place via style-background`() {
        val js = applyJs(AnnotationHighlight("ann1", "text", "#abc"))
        // The in-place update path sets style.background on the existing mark.
        assertTrue(js.contains("existing.style.background"))
    }

    @Test
    fun `new annotations are located via context-aware locateRange not window-find`() {
        // window.find() matches the FIRST occurrence document-wide — wrong when the highlighted
        // text repeats. The new algorithm builds a flat text index and picks the occurrence whose
        // surrounding context matches the stored b/a.
        val js = applyJs(AnnotationHighlight("ann1", "text", "#abc"))
        assertFalse(
            "window.find() must not be used for annotation location (it picks first match)",
            js.contains("window.find("),
        )
        assertTrue("locateRange function defined", js.contains("locateRange"))
        assertTrue("flat text index built via TreeWalker", js.contains("createTreeWalker"))
        // Context scoring: the occurrence whose surrounding text matches ann.b / ann.a wins.
        assertTrue("scores against ann.b context", js.contains("ann.b"))
        assertTrue("scores against ann.a context", js.contains("ann.a"))
    }

    @Test
    fun `locateRange short-circuits to first match when before and after are both empty`() {
        // Back-compat path for legacy annotations stored before context capture: empty b/a
        // must reproduce the old window.find() first-match behaviour.
        val js = applyJs(AnnotationHighlight("ann1", "text", "#abc"))
        // The first-match branch: `if (!ann.b && !ann.a) { bestIdx = idx; break; }`
        assertTrue("first-match branch present", js.contains("!ann.b && !ann.a"))
    }

    @Test
    fun `text nodes inside existing annotation marks are skipped when building the flat index`() {
        // The b/a context was captured against the unmarked DOM, so the text inside a previously
        // placed [data-riffle-ann] mark must not appear in the flat index — otherwise a new
        // annotation whose context happens to surround an earlier mark could fail to match.
        val js = applyJs(AnnotationHighlight("ann1", "text", "#abc"))
        assertTrue("skip text inside existing marks", js.contains("[data-riffle-ann]"))
    }

    @Test
    fun `flat index is invalidated after each mark insertion to track DOM mutation`() {
        // surroundContents() splits the start/end text nodes — the cached flat index becomes
        // stale, so subsequent annotations in the same call must rebuild it.
        val js = applyJs(
            AnnotationHighlight("ann1", "first", "#abc"),
            AnnotationHighlight("ann2", "second", "#abc"),
        )
        assertTrue("flatIdx invalidated post-insert", js.contains("flatIdx = null"))
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
    fun `AnnotationHighlight before and after default to empty strings`() {
        val ann = AnnotationHighlight("id", "text", "#000")
        assertEquals("", ann.before)
        assertEquals("", ann.after)
    }

    @Test
    fun `AnnotationHighlight equality and copy`() {
        val ann = AnnotationHighlight("id", "text", "#000", hasNote = true, before = "b", after = "a")
        assertEquals(ann, AnnotationHighlight("id", "text", "#000", hasNote = true, before = "b", after = "a"))
        assertFalse("hasNote participates in equality", ann == ann.copy(hasNote = false))
        assertFalse("before participates in equality", ann == ann.copy(before = "x"))
        assertFalse("after participates in equality", ann == ann.copy(after = "x"))
    }

    // ── applySearchHighlightsJs ───────────────────────────────────────────────

    @Test
    fun `applySearchHighlightsJs embeds inactive text array in JS output`() {
        val js = ContinuousStyleInjector.applySearchHighlightsJs(
            inactiveTexts = listOf("hello", "world"),
            inactiveCssColor = "rgba(253,230,138,0.30)",
            activeText = null,
            activeProgression = -1f,
            activeCssColor = "rgba(245,166,35,0.30)",
        )
        assertTrue("JS should contain 'hello'", js.contains("'hello'"))
        assertTrue("JS should contain 'world'", js.contains("'world'"))
    }

    @Test
    fun `applySearchHighlightsJs embeds colors in JS output`() {
        val active = "rgba(245,166,35,0.30)"
        val inactive = "rgba(253,230,138,0.30)"
        val js = ContinuousStyleInjector.applySearchHighlightsJs(
            inactiveTexts = listOf("text"),
            inactiveCssColor = inactive,
            activeText = "text",
            activeProgression = 0.5f,
            activeCssColor = active,
        )
        assertTrue(js.contains(active))
        assertTrue(js.contains(inactive))
    }

    @Test
    fun `applySearchHighlightsJs embeds active text and progression when provided`() {
        val js = ContinuousStyleInjector.applySearchHighlightsJs(
            inactiveTexts = listOf("Dune"),
            inactiveCssColor = "rgba(253,230,138,0.30)",
            activeText = "Dune",
            activeProgression = 0.42f,
            activeCssColor = "rgba(245,166,35,0.30)",
        )
        assertTrue("active text in output", js.contains("'Dune'"))
        assertTrue("progression in output", js.contains("0.42"))
    }

    @Test
    fun `applySearchHighlightsJs passes null active text when chapter has no active result`() {
        val js = ContinuousStyleInjector.applySearchHighlightsJs(
            inactiveTexts = listOf("word"),
            inactiveCssColor = "rgba(253,230,138,0.30)",
            activeText = null,
            activeProgression = -1f,
            activeCssColor = "rgba(245,166,35,0.30)",
        )
        assertTrue("null literal in output", js.contains(",null,"))
    }

    @Test
    fun `applySearchHighlightsJs escapes single quotes in text`() {
        val js = ContinuousStyleInjector.applySearchHighlightsJs(
            inactiveTexts = listOf("it's"),
            inactiveCssColor = "rgba(253,230,138,0.30)",
            activeText = "it's",
            activeProgression = 0.5f,
            activeCssColor = "rgba(245,166,35,0.30)",
        )
        assertTrue("single quote escaped", js.contains("\\'"))
    }

    @Test
    fun `applySearchHighlightsJs uses data-riffle-si for inactive and data-riffle-sa for active`() {
        val js = ContinuousStyleInjector.applySearchHighlightsJs(
            inactiveTexts = listOf("word"),
            inactiveCssColor = "rgba(253,230,138,0.30)",
            activeText = "word",
            activeProgression = 0.5f,
            activeCssColor = "rgba(245,166,35,0.30)",
        )
        assertTrue("inactive marker attribute present", js.contains("data-riffle-si"))
        assertTrue("active marker attribute present", js.contains("data-riffle-sa"))
    }

    @Test
    fun `CLEAR_SEARCH_HIGHLIGHTS_JS targets both data-riffle-si and data-riffle-sa`() {
        assertTrue(ContinuousStyleInjector.CLEAR_SEARCH_HIGHLIGHTS_JS.contains("data-riffle-si"))
        assertTrue(ContinuousStyleInjector.CLEAR_SEARCH_HIGHLIGHTS_JS.contains("data-riffle-sa"))
    }
}
