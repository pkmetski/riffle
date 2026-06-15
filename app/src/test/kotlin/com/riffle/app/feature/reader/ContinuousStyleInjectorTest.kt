package com.riffle.app.feature.reader

import com.riffle.core.domain.FormattingPreferences
import com.riffle.core.domain.ReaderFontFamily
import com.riffle.core.domain.ReaderTheme
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ContinuousStyleInjectorTest {

    @Test
    fun `default prefs — fontSize 1rem, pageMargins 1, no lineHeight variable`() {
        val js = ContinuousStyleInjector.buildVariableInjectionJs(FormattingPreferences())
        assertTrue("fontSize", js.contains("--USER__fontSize', '1.0rem'"))
        assertTrue("pageMargins", js.contains("--USER__pageMargins', '1.0'"))
        // lineSpacing == DEFAULT (1.2f) → variable must NOT be set
        assertFalse("lineHeight not set on default", js.contains("setProperty('--USER__lineHeight'"))
        assertTrue("lineHeight removed on default", js.contains("removeProperty('--USER__lineHeight'"))
    }

    @Test
    fun `non-default lineSpacing sets --USER__lineHeight`() {
        val js = ContinuousStyleInjector.buildVariableInjectionJs(
            FormattingPreferences(lineSpacing = 1.6f)
        )
        assertTrue(js.contains("setProperty('--USER__lineHeight', '1.6'"))
    }

    @Test
    fun `justifyText sets --USER__textAlign to justify`() {
        val js = ContinuousStyleInjector.buildVariableInjectionJs(
            FormattingPreferences(justifyText = true)
        )
        assertTrue(js.contains("setProperty('--USER__textAlign', 'justify'"))
    }

    @Test
    fun `non-justify removes --USER__textAlign`() {
        val js = ContinuousStyleInjector.buildVariableInjectionJs(
            FormattingPreferences(justifyText = false)
        )
        assertTrue(js.contains("removeProperty('--USER__textAlign'"))
    }

    @Test
    fun `Serif font family (default) — no fontFamily variable set`() {
        val js = ContinuousStyleInjector.buildVariableInjectionJs(
            FormattingPreferences(fontFamily = ReaderFontFamily.Serif)
        )
        assertFalse(js.contains("setProperty('--USER__fontFamily'"))
        assertTrue(js.contains("removeProperty('--USER__fontFamily'"))
    }

    @Test
    fun `SansSerif family sets sans-serif`() {
        val js = ContinuousStyleInjector.buildVariableInjectionJs(
            FormattingPreferences(fontFamily = ReaderFontFamily.SansSerif)
        )
        assertTrue(js.contains("setProperty('--USER__fontFamily', 'sans-serif'"))
    }

    @Test
    fun `Dark theme — textColor NOT set (DarkDim only)`() {
        val js = ContinuousStyleInjector.buildVariableInjectionJs(
            FormattingPreferences(theme = ReaderTheme.Dark)
        )
        assertFalse(js.contains("setProperty('--USER__textColor'"))
    }

    @Test
    fun `DarkDim theme — textColor set to AAAAAA`() {
        val js = ContinuousStyleInjector.buildVariableInjectionJs(
            FormattingPreferences(theme = ReaderTheme.DarkDim)
        )
        assertTrue(js.contains("setProperty('--USER__textColor', '#AAAAAA'"))
    }
}
