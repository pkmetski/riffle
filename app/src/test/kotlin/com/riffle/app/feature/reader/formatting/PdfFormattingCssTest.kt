package com.riffle.app.feature.reader.formatting

import com.riffle.core.domain.FormattingPreferences
import com.riffle.core.domain.ReaderFontFamily
import com.riffle.core.domain.ReaderTheme
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PdfFormattingCssTest {
    @Test
    fun `dark theme sets dark background css variable`() {
        val js = buildPdfFormattingCss(FormattingPreferences().copy(theme = ReaderTheme.Dark))
        assertTrue(js.contains("--pdf-bg"))
        assertTrue(js.contains("#0")) // dark hex prefix
    }

    @Test
    fun `sepia theme sets sepia background`() {
        val js = buildPdfFormattingCss(FormattingPreferences().copy(theme = ReaderTheme.Sepia))
        assertTrue(Regex("--pdf-bg.*[fF]4[eE]").containsMatchIn(js))
    }

    @Test
    fun `margins are applied as viewer padding`() {
        val js = buildPdfFormattingCss(FormattingPreferences().copy(margins = 1.5f))
        assertTrue(js.contains("padding-left"))
        assertTrue(js.contains("1.5"))
    }

    @Test
    fun `font family and line height do not throw and produce no font css`() {
        val prefs = FormattingPreferences().copy(
            fontFamily = ReaderFontFamily.OpenDyslexic,
            lineSpacing = 1.8f,
        )
        val js = buildPdfFormattingCss(prefs)
        assertFalse(js.contains("font-family"))
        assertFalse(js.contains("line-height"))
    }
}
