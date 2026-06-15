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
    fun `text-size-adjust 100 percent — prevents Chrome font inflation`() {
        val js = css(FormattingPreferences())
        assertTrue(js.contains("-webkit-text-size-adjust:100%!important"))
        assertTrue(js.contains("text-size-adjust:100%!important"))
    }

    @Test
    fun `paragraph elements reset to 1rem to strip EPUB per-element font-size overrides`() {
        val js = css(FormattingPreferences())
        // Mirrors ReadiumCSS advanced-mode: p,li,dd,div{font-size:1rem!important}
        assertTrue("p,li,dd,div block present", js.contains("p,li,dd,div{"))
        assertTrue("1rem reset present", js.contains("font-size:1rem!important"))
    }

    @Test
    fun `Serif uses generic serif — matches Readium null mapping`() {
        val js = css(FormattingPreferences(fontFamily = ReaderFontFamily.Serif))
        // FormattingPreferencesMapper maps Serif → null (Readium default = system serif).
        // Continuous mode must use the same generic so both modes pick the same system font.
        assertTrue("generic serif keyword present", js.contains("font-family:serif"))
        assertFalse("no Georgia override", js.contains("Georgia"))
    }

    @Test
    fun `SansSerif uses generic sans-serif — matches Readium mapping`() {
        val js = css(FormattingPreferences(fontFamily = ReaderFontFamily.SansSerif))
        assertTrue(js.contains("font-family:sans-serif"))
    }

    @Test
    fun `Monospace uses generic monospace — matches Readium mapping`() {
        val js = css(FormattingPreferences(fontFamily = ReaderFontFamily.Monospace))
        assertTrue(js.contains("font-family:monospace"))
        assertFalse("no Courier override", js.contains("Courier"))
    }

    @Test
    fun `Literata injects font-face and uses Literata family`() {
        val js = css(FormattingPreferences(fontFamily = ReaderFontFamily.Literata))
        assertTrue("@font-face for Literata", js.contains("font-family:Literata"))
        assertTrue("ttf url present", js.contains("Literata-Regular.ttf"))
    }

    @Test
    fun `Merriweather injects font-face and uses Merriweather family`() {
        val js = css(FormattingPreferences(fontFamily = ReaderFontFamily.Merriweather))
        assertTrue("@font-face for Merriweather", js.contains("font-family:Merriweather"))
        assertTrue("ttf url present", js.contains("Merriweather-Regular.ttf"))
    }

    @Test
    fun `OpenDyslexic injects font-face and uses OpenDyslexic family`() {
        val js = css(FormattingPreferences(fontFamily = ReaderFontFamily.OpenDyslexic))
        assertTrue("@font-face for OpenDyslexic", js.contains("font-family:OpenDyslexic"))
        assertTrue("otf url present", js.contains("OpenDyslexic-Regular.otf"))
    }

    @Test
    fun `Serif does not inject font-face declarations`() {
        val js = css(FormattingPreferences(fontFamily = ReaderFontFamily.Serif))
        assertFalse("no @font-face for Serif", js.contains("@font-face"))
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
