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
    fun `Original (default) emits no font override — publisher font, matching Readium null mapping`() {
        val s = attr(FormattingPreferences(fontFamily = ReaderFontFamily.Original))
        assertFalse("no font override", s.contains("--USER__fontOverride"))
        assertFalse("no font family", s.contains("--USER__fontFamily"))
    }

    // Regression: the generic "Serif" chip must emit a real serif override, not passthrough.
    @Test
    fun `Serif sets fontOverride and quoted CSS serif family`() {
        val s = attr(FormattingPreferences(fontFamily = ReaderFontFamily.Serif))
        assertTrue(s.contains("--USER__fontOverride: readium-font-on !important;"))
        assertTrue(s.contains("--USER__fontFamily: \"serif\" !important;"))
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

    // ── theme-switch tile-cache invalidation ───────────────────────────────────
    //
    // WebSettingsCompat.setOffscreenPreRaster(true) (see #413) is required to stop the
    // chapter-boundary blank flash in Continuous mode, but its side-effect is that a bare
    // :root style-attribute mutation doesn't invalidate the rasterised tiles. The CSSOM
    // updates and getComputedStyle returns the new values, but the composited image on
    // screen still shows the pre-change theme — visible bug: Sepia → Dim leaves sepia
    // background AND the text vanishes entirely.
    //
    // Fix: after the setAttribute, toggle `visibility` on :root to force Chromium to
    // repaint the entire subtree. Restore is idempotent (unconditional `''`) and covered
    // by BOTH requestAnimationFrame AND setTimeout so overlapping rapid switches don't
    // capture a mid-flight `'hidden'` as the "previous" value (which the earlier version
    // did and left the WebView stuck hidden), and a backgrounded WebView with RAF paused
    // still gets restored via the setTimeout fallback.

    @Test
    fun `buildStyleInjectionJs sets root visibility hidden to force Chromium tile invalidation`() {
        val js = ContinuousStyleInjector.buildStyleInjectionJs(FormattingPreferences())
        assertTrue(
            "must set documentElement visibility to 'hidden' after the attribute change — " +
                "this is the ONLY reliable way to invalidate the raster tiles pre-raster keeps alive",
            js.contains("de.style.visibility = 'hidden'"),
        )
    }

    @Test
    fun `buildStyleInjectionJs restores visibility unconditionally not via captured previous value`() {
        // The earlier iteration read `var prevVis = de.style.visibility` and restored via
        // `prevVis || ''`. That looked correct but broke under rapid theme switches: the
        // second call captured `'hidden'` (mid-flight from the first still-pending toggle)
        // and RAF restored to `'hidden'`, stuck-hiding the WebView. This assertion pins
        // the always-empty-string restore so the regression can't return.
        val js = ContinuousStyleInjector.buildStyleInjectionJs(FormattingPreferences())
        assertTrue("restore sets visibility to '' unconditionally", js.contains("de.style.visibility = '';"))
        assertFalse(
            "must NOT restore via captured previous value — race with overlapping switches",
            js.contains("prevVis"),
        )
    }

    @Test
    fun `buildStyleInjectionJs schedules restore via both requestAnimationFrame and setTimeout`() {
        // requestAnimationFrame is paused for backgrounded WebViews; the setTimeout is the
        // belt-and-suspenders fallback so a WV that never comes back on-screen isn't left
        // stuck at `visibility: hidden`.
        val js = ContinuousStyleInjector.buildStyleInjectionJs(FormattingPreferences())
        assertTrue("schedules restore via requestAnimationFrame", js.contains("requestAnimationFrame(restore)"))
        assertTrue("schedules restore via setTimeout fallback", js.contains("setTimeout(restore,"))
    }

    // ── night-mode !important survival ──────────────────────────────────────────
    //
    // ReadiumCSS night mode injects `background-color: transparent !important` on every
    // `:not(a)` descendant of :root. The continuous-mode `<mark>`s are exactly that. Without
    // inline `!important` on each mark's background the highlight paints transparent on Dark
    // / DarkDim — the whole "highlights don't render on dark themes" bug. These assertions
    // pin the `!important` marker on every mark-emitting JS path so a plain revert flips red.

    @Test
    fun `applyAnnotationHighlightsJs new-mark background carries !important`() {
        val js = ContinuousStyleInjector.applyAnnotationHighlightsJs(
            listOf(AnnotationHighlight("id", "text", "rgba(251,191,36,0.50)")),
        )
        // The new-mark assignment is `mark.style.cssText = 'background:' + ann.c + '<suffix>';`.
        // The suffix, embedded verbatim into the JS string, is what carries `!important`.
        assertTrue(
            "new-mark cssText must contain '!important' — actual JS lacks it",
            js.contains("'background:' + ann.c + ' !important;color:inherit;'"),
        )
    }

    @Test
    fun `applyAnnotationHighlightsJs existing-mark recolor carries !important`() {
        // Updating the colour of an already-wrapped annotation must use the same `!important`
        // suffix as the initial wrap. The `.style.background = ann.c` shortcut without
        // `!important` was the original bug on this path.
        val js = ContinuousStyleInjector.applyAnnotationHighlightsJs(
            listOf(AnnotationHighlight("id", "text", "rgba(0,0,0,0.50)")),
        )
        assertTrue(
            "existing-mark recolor must contain '!important' — actual JS lacks it",
            js.contains("m.style.cssText = 'background:' + ann.c + ' !important;color:inherit;'"),
        )
    }

    @Test
    fun `applySearchHighlightsJs inactive-mark background carries !important`() {
        val js = ContinuousStyleInjector.applySearchHighlightsJs(
            inactiveTexts = listOf("query"),
            inactiveCssColor = "rgba(245,166,35,0.30)",
            activeText = null,
            activeProgression = 0f,
            activeCssColor = "rgba(245,166,35,0.30)",
        )
        assertTrue(
            "inactive search mark cssText must contain '!important' — actual JS lacks it",
            js.contains("'background:' + inactiveCss + ' !important;color:inherit;'"),
        )
    }

    @Test
    fun `applySearchHighlightsJs active-mark background carries !important`() {
        val js = ContinuousStyleInjector.applySearchHighlightsJs(
            inactiveTexts = listOf("query"),
            inactiveCssColor = "rgba(245,166,35,0.30)",
            activeText = "query",
            activeProgression = 0.5f,
            activeCssColor = "rgba(245,166,35,0.50)",
        )
        assertTrue(
            "active search mark cssText must contain '!important' — actual JS lacks it",
            js.contains("'background:' + activeCss + ' !important;color:inherit;'"),
        )
    }

    @Test
    fun `highlightTextJs readaloud-sentence mark background carries !important`() {
        val js = ContinuousStyleInjector.highlightTextJs(
            "the quick brown fox",
            "rgba(56,189,248,0.50)",
        )
        // Kotlin-interpolated cssColor bakes into the JS literal — pin the whole background:...
        // decl including the required `!important` marker.
        assertTrue(
            "readaloud-sentence mark cssText must contain '!important' — actual JS lacks it",
            js.contains("'background:rgba(56,189,248,0.50) !important;color:inherit;'"),
        )
    }

    // ── highlight helpers (unchanged) ───────────────────────────────────────────

    @Test
    fun `highlightIdJs uses getElementById not window find so the exact span is highlighted`() {
        // Regression (recording 20260707_162341): continuous mode used `window.find(text)`
        // which lands on the first occurrence in the doc — for repeated phrases or heading
        // text that reappears in body, the mark ended up mid-section instead of at the
        // resolver-picked cd-N. Cadence's `cd-N` ids ARE in the DOM (the tokeniser wrote
        // them) and are chapter-unique, so getElementById is authoritative.
        val js = ContinuousStyleInjector.highlightIdJs("cd-181", "rgba(251,191,36,0.50)")
        assertTrue("must use getElementById for exact-span placement", js.contains("document.getElementById('cd-181')"))
        assertTrue("must NOT fall back to window.find for id-anchored paint", !js.contains("window.find"))
        assertTrue("must clear any prior _riffle_hl mark before applying new one", js.contains("_riffle_hl"))
    }

    @Test
    fun `highlightIdJs blank id short-circuits to CLEAR to avoid a getElementById('') no-op`() {
        val js = ContinuousStyleInjector.highlightIdJs("", "rgba(0,0,0,1)")
        assertEquals(ContinuousStyleInjector.CLEAR_HIGHLIGHT_JS, js)
    }

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
    fun `existing marks are updated in-place via cssText rewrite`() {
        val js = applyJs(AnnotationHighlight("ann1", "text", "#abc"))
        // Multi-paragraph annotations produce one <mark> per block — the in-place update path
        // walks every mark that already shares the id and recolours each. cssText (not the
        // .background shortcut) is required so the `!important` suffix survives ReadiumCSS
        // night-mode's `background-color: transparent !important` on `:not(a)`.
        assertTrue(js.contains("m.style.cssText"))
        // And it relocates only for NEW annotations (after the in-place branch returns).
        assertTrue(js.contains("existingAll"))
    }

    @Test
    fun `new annotations are located via context-aware locateRanges not window-find`() {
        // window.find() matches the FIRST occurrence document-wide — wrong when the highlighted
        // text repeats. The new algorithm builds a flat text index and picks the occurrence whose
        // surrounding context matches the stored b/a.
        val js = applyJs(AnnotationHighlight("ann1", "text", "#abc"))
        assertTrue("locateRanges function defined", js.contains("locateRanges"))
        assertTrue("flat text index built via TreeWalker", js.contains("createTreeWalker"))
        // The match strictly compares to ann.b / ann.a — no fuzzy substring scoring that could
        // pick a wrong occurrence sharing a common 10-char suffix/prefix.
        assertTrue("exact before-context comparison", js.contains("beforeWin === before"))
        assertTrue("exact after-context comparison", js.contains("afterWin === after"))
    }

    @Test
    fun `multi-paragraph snippets are chunked on newlines and located per block`() {
        // Selection.toString() glues block content with "\n" / "\n\n"; the flat index has NO
        // separators between blocks, so a multi-paragraph snippet would never match as a single
        // string. The renderer splits on /\n+/ and locates each chunk sequentially, anchored to
        // after the previous chunk's end so duplicates earlier in the document can't capture it.
        val js = applyJs(AnnotationHighlight("ann1", "text", "#abc"))
        assertTrue("snippet split on newlines", js.contains("ann.t.split(/\\n+/)"))
        assertTrue("sequential anchor via searchFrom", js.contains("searchFrom"))
    }

    @Test
    fun `cross-block ranges are rejected via __riffleSafeWrap to avoid reparenting block elements`() {
        // extractContents() + insertNode() reparents block descendants as inline children of
        // <mark>, orphaning the tail of the last partially-selected block. __riffleSafeWrap
        // checks the block ancestor of each endpoint and refuses to wrap when they differ.
        val js = applyJs(AnnotationHighlight("ann1", "text", "#abc"))
        assertTrue("safe-wrap helper installed", js.contains("__riffleSafeWrap"))
        assertTrue("wrap call goes through the helper", js.contains("window.__riffleSafeWrap(range, mark)"))
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
