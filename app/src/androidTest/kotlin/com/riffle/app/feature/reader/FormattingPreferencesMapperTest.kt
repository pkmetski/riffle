package com.riffle.app.feature.reader

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.riffle.core.domain.FormattingPreferences
import com.riffle.core.domain.ReaderFontFamily
import com.riffle.core.domain.ReaderOrientation
import com.riffle.core.domain.ReaderTheme
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.readium.r2.navigator.preferences.Theme

@RunWith(AndroidJUnit4::class)
class FormattingPreferencesMapperTest {

    @Test
    fun fontSizeMapsToEpubPreferencesFontSizeAsDouble() {
        val result = FormattingPreferences(fontSize = 1.5f).toEpubPreferences()
        assertEquals(1.5, result.fontSize!!, 0.001)
    }

    @Test
    fun lightThemeMapsToLIGHT() {
        val result = FormattingPreferences(theme = ReaderTheme.Light).toEpubPreferences()
        assertEquals(Theme.LIGHT, result.theme)
    }

    @Test
    fun darkThemeMapsToDARK() {
        val result = FormattingPreferences(theme = ReaderTheme.Dark).toEpubPreferences()
        assertEquals(Theme.DARK, result.theme)
    }

    @Test
    fun sepiaThemeMapsToSEPIA() {
        val result = FormattingPreferences(theme = ReaderTheme.Sepia).toEpubPreferences()
        assertEquals(Theme.SEPIA, result.theme)
    }

    @Test
    fun serifFontFamilyMapsToCssSerif() {
        val result = FormattingPreferences(fontFamily = ReaderFontFamily.Serif).toEpubPreferences()
        assertEquals("serif", result.fontFamily?.name)
    }

    @Test
    fun sansSerifFontFamilyMapsToCssSansSerif() {
        val result = FormattingPreferences(fontFamily = ReaderFontFamily.SansSerif).toEpubPreferences()
        assertEquals("sans-serif", result.fontFamily?.name)
    }

    @Test
    fun monospaceFontFamilyMapsToCssMonospace() {
        val result = FormattingPreferences(fontFamily = ReaderFontFamily.Monospace).toEpubPreferences()
        assertEquals("monospace", result.fontFamily?.name)
    }

    @Test
    fun literataFontFamilyMapsByName() {
        val result = FormattingPreferences(fontFamily = ReaderFontFamily.Literata).toEpubPreferences()
        assertEquals("Literata", result.fontFamily?.name)
    }

    @Test
    fun merriweatherFontFamilyMapsByName() {
        val result = FormattingPreferences(fontFamily = ReaderFontFamily.Merriweather).toEpubPreferences()
        assertEquals("Merriweather", result.fontFamily?.name)
    }

    @Test
    fun openDyslexicFontFamilyMapsByName() {
        val result = FormattingPreferences(fontFamily = ReaderFontFamily.OpenDyslexic).toEpubPreferences()
        assertEquals("OpenDyslexic", result.fontFamily?.name)
    }

    @Test
    fun lineSpacingMapsToLineHeight() {
        val result = FormattingPreferences(lineSpacing = 1.8f).toEpubPreferences()
        assertEquals(1.8, result.lineHeight!!, 0.001)
    }

    @Test
    fun marginsMapsDirectlyToPageMargins() {
        val result = FormattingPreferences(margins = 1.4f).toEpubPreferences()
        assertEquals(1.4, result.pageMargins!!, 0.001)
    }

    @Test
    fun horizontalOrientationMapsToScrollFalse() {
        val result = FormattingPreferences(orientation = ReaderOrientation.Horizontal).toEpubPreferences()
        assertEquals(false, result.scroll)
    }

    @Test
    fun verticalOrientationMapsToScrollTrue() {
        val result = FormattingPreferences(orientation = ReaderOrientation.Vertical).toEpubPreferences()
        assertEquals(true, result.scroll)
    }

}
