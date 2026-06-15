package com.riffle.app.feature.reader

import com.riffle.core.domain.FormattingPreferences
import com.riffle.core.domain.ReaderFontFamily
import com.riffle.core.domain.ReaderTheme
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ContinuousStyleInjectorTest {

    private fun css(prefs: FormattingPreferences): String =
        ContinuousStyleInjector.buildStyleInjectionJs(prefs)

    @Test
    fun `output injects a style element with id _riffle_user`() {
        val js = css(FormattingPreferences())
        assertTrue(js.contains("_riffle_user"))
        assertTrue(js.contains("createElement('style')"))
        assertTrue(js.contains("s.textContent"))
    }

    @Test
    fun `default prefs — font-size 1rem on html`() {
        val js = css(FormattingPreferences())
        assertTrue("fontSize 1.0rem", js.contains("font-size:1.0rem!important"))
    }

    @Test
    fun `non-default fontSize reflected in CSS`() {
        val js = css(FormattingPreferences(fontSize = 1.5f))
        assertTrue(js.contains("font-size:1.5rem!important"))
    }

    @Test
    fun `default lineSpacing applied to body and paragraph elements`() {
        val js = css(FormattingPreferences())
        assertTrue("line-height on body", js.contains("line-height:1.2!important"))
    }

    @Test
    fun `non-default lineSpacing set`() {
        val js = css(FormattingPreferences(lineSpacing = 1.6f))
        assertTrue(js.contains("line-height:1.6!important"))
    }

    @Test
    fun `justifyText true — text-align justify`() {
        val js = css(FormattingPreferences(justifyText = true))
        assertTrue(js.contains("text-align:justify!important"))
        assertFalse(js.contains("text-align:left!important"))
    }

    @Test
    fun `justifyText false (default) — text-align left overrides EPUB justify`() {
        val js = css(FormattingPreferences(justifyText = false))
        assertTrue(js.contains("text-align:left!important"))
    }

    @Test
    fun `default margins — padding applied`() {
        val js = css(FormattingPreferences())
        // margins=1.0 → paddingPct = (1.0*6).toInt() = 6
        assertTrue(js.contains("padding-left:6%!important"))
        assertTrue(js.contains("padding-right:6%!important"))
    }

    @Test
    fun `Serif font family (default) — no font-family override`() {
        val js = css(FormattingPreferences(fontFamily = ReaderFontFamily.Serif))
        assertFalse("Serif keeps EPUB font", js.contains("font-family:"))
    }

    @Test
    fun `SansSerif sets Arial stack`() {
        val js = css(FormattingPreferences(fontFamily = ReaderFontFamily.SansSerif))
        assertTrue(js.contains("font-family:Arial"))
    }

    @Test
    fun `Monospace sets Courier stack`() {
        val js = css(FormattingPreferences(fontFamily = ReaderFontFamily.Monospace))
        assertTrue(js.contains("font-family:"))
        assertTrue(js.contains("Courier"))
    }

    @Test
    fun `Dark theme — black background and white text`() {
        val js = css(FormattingPreferences(theme = ReaderTheme.Dark))
        assertTrue(js.contains("background-color:#000000!important"))
        assertTrue(js.contains("color:#FEFEFE!important"))
    }

    @Test
    fun `DarkDim theme — black background and dim grey text`() {
        val js = css(FormattingPreferences(theme = ReaderTheme.DarkDim))
        assertTrue(js.contains("background-color:#000000!important"))
        assertTrue(js.contains("color:#AAAAAA!important"))
    }

    @Test
    fun `Sepia theme — sepia background and dark text`() {
        val js = css(FormattingPreferences(theme = ReaderTheme.Sepia))
        assertTrue(js.contains("background-color:#FAF4E8!important"))
        assertTrue(js.contains("color:#121212!important"))
    }

    @Test
    fun `Light theme — no background or text colour override`() {
        val js = css(FormattingPreferences(theme = ReaderTheme.Light))
        assertFalse("no bg override on Light", js.contains("background-color:"))
        assertFalse("no fg override on Light", js.contains("color:#"))
    }

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
