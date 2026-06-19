package com.riffle.app.feature.reader

import com.riffle.core.domain.FormattingPreferences
import com.riffle.core.domain.ReaderFontFamily
import com.riffle.core.domain.ReaderTheme
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
    }

    // ── highlight helpers (unchanged) ───────────────────────────────────────────

    @Test
    fun `highlightTextJs escapes single quotes in text`() {
        val js = ContinuousStyleInjector.highlightTextJs("it's a test")
        assertTrue(js.contains("it\\'s a test"))
        assertFalse(js.contains("it's a test"))
    }

    @Test
    fun `highlightTextJs escapes newlines in text`() {
        val js = ContinuousStyleInjector.highlightTextJs("line one\nline two")
        assertTrue(js.contains("\\n"))
        assertFalse(js.contains("line one\nline two"))
    }
}
