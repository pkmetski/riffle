package com.riffle.app.feature.reader

import com.riffle.core.domain.FormattingPreferences
import com.riffle.core.domain.ReaderFontFamily
import com.riffle.core.domain.ReaderOrientation
import com.riffle.core.domain.ReaderTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.readium.r2.navigator.preferences.Theme

class FormattingPreferencesMapperTest {

    @Test
    fun `fontSize maps to EpubPreferences fontSize as Double`() {
        val result = FormattingPreferences(fontSize = 1.5f).toEpubPreferences()
        assertEquals(1.5, result.fontSize!!, 0.001)
    }

    @Test
    fun `Light theme maps to LIGHT`() {
        val result = FormattingPreferences(theme = ReaderTheme.Light).toEpubPreferences()
        assertEquals(Theme.LIGHT, result.theme)
    }

    @Test
    fun `Dark theme maps to DARK`() {
        val result = FormattingPreferences(theme = ReaderTheme.Dark).toEpubPreferences()
        assertEquals(Theme.DARK, result.theme)
    }

    @Test
    fun `Sepia theme maps to SEPIA`() {
        val result = FormattingPreferences(theme = ReaderTheme.Sepia).toEpubPreferences()
        assertEquals(Theme.SEPIA, result.theme)
    }

    @Test
    fun `Serif fontFamily maps to css serif`() {
        val result = FormattingPreferences(fontFamily = ReaderFontFamily.Serif).toEpubPreferences()
        assertEquals("serif", result.fontFamily?.name)
    }

    @Test
    fun `SansSerif fontFamily maps to css sans-serif`() {
        val result = FormattingPreferences(fontFamily = ReaderFontFamily.SansSerif).toEpubPreferences()
        assertEquals("sans-serif", result.fontFamily?.name)
    }

    @Test
    fun `Monospace fontFamily maps to css monospace`() {
        val result = FormattingPreferences(fontFamily = ReaderFontFamily.Monospace).toEpubPreferences()
        assertEquals("monospace", result.fontFamily?.name)
    }

    @Test
    fun `Literata fontFamily maps by name`() {
        val result = FormattingPreferences(fontFamily = ReaderFontFamily.Literata).toEpubPreferences()
        assertEquals("Literata", result.fontFamily?.name)
    }

    @Test
    fun `Merriweather fontFamily maps by name`() {
        val result = FormattingPreferences(fontFamily = ReaderFontFamily.Merriweather).toEpubPreferences()
        assertEquals("Merriweather", result.fontFamily?.name)
    }

    @Test
    fun `OpenDyslexic fontFamily maps by name`() {
        val result = FormattingPreferences(fontFamily = ReaderFontFamily.OpenDyslexic).toEpubPreferences()
        assertEquals("OpenDyslexic", result.fontFamily?.name)
    }

    @Test
    fun `lineSpacing maps to lineHeight`() {
        val result = FormattingPreferences(lineSpacing = 1.8f).toEpubPreferences()
        assertEquals(1.8, result.lineHeight!!, 0.001)
    }

    @Test
    fun `margins float maps directly to pageMargins`() {
        val result = FormattingPreferences(margins = 1.4f).toEpubPreferences()
        assertEquals(1.4, result.pageMargins!!, 0.001)
    }

    @Test
    fun `Paginated orientation maps to scroll false`() {
        val result = FormattingPreferences(orientation = ReaderOrientation.Paginated).toEpubPreferences()
        assertFalse(result.scroll!!)
    }

    @Test
    fun `Scroll orientation maps to scroll true`() {
        val result = FormattingPreferences(orientation = ReaderOrientation.Scroll).toEpubPreferences()
        assertTrue(result.scroll!!)
    }
}
