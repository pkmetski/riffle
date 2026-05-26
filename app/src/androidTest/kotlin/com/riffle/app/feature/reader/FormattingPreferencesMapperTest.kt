@file:OptIn(org.readium.r2.shared.ExperimentalReadiumApi::class)

package com.riffle.app.feature.reader

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.riffle.core.domain.FormattingPreferences
import com.riffle.core.domain.ReaderFontFamily
import com.riffle.core.domain.ReaderOrientation
import com.riffle.core.domain.ReaderTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.readium.r2.navigator.epub.EpubPreferences
import org.readium.r2.navigator.preferences.ColumnCount
import org.readium.r2.navigator.preferences.Spread
import org.readium.r2.navigator.preferences.Theme
import org.readium.r2.shared.ExperimentalReadiumApi

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

    @OptIn(ExperimentalReadiumApi::class)
    @Test
    fun defaultFormattingPreferencesMappingDiffersFromReadiumDefaultEpubPreferences() {
        // Regression guard: if FormattingPreferences().toEpubPreferences() were equal to
        // EpubPreferences(), omitting initialPreferences in createFragmentFactory would be
        // harmless. It is not — toEpubPreferences() always sets explicit values (theme, font,
        // scroll, publisherStyles, etc.) while EpubPreferences() leaves all fields null,
        // causing Readium to use its own defaults which differ from ours. Without
        // initialPreferences the fragment renders one frame with wrong settings then flashes
        // when submitPreferences applies the real values.
        assertNotEquals(EpubPreferences(), FormattingPreferences().toEpubPreferences())
    }

    // --- Double-page / fixed-layout mapping ---

    @Test
    fun doublePageInLandscapeReflowableProducesTwoColumns() {
        val result = FormattingPreferences(doublePageSpread = true).toEpubPreferences(
            isLandscape = true,
            isFixedLayout = false,
        )
        assertEquals(ColumnCount.TWO, result.columnCount)
        assertNull(result.spread)
    }

    @Test
    fun doublePageInPortraitReflowableDeferToReadiumDefault() {
        val result = FormattingPreferences(doublePageSpread = true).toEpubPreferences(
            isLandscape = false,
            isFixedLayout = false,
        )
        assertNull(result.columnCount)
    }

    @Test
    fun doublePageOffInLandscapeReflowableDeferToReadiumDefault() {
        val result = FormattingPreferences(doublePageSpread = false).toEpubPreferences(
            isLandscape = true,
            isFixedLayout = false,
        )
        assertNull(result.columnCount)
    }

    @Test
    fun verticalOrientationDeferToReadiumDefault() {
        val result = FormattingPreferences(
            orientation = ReaderOrientation.Vertical,
            doublePageSpread = true,
        ).toEpubPreferences(isLandscape = true, isFixedLayout = false)
        assertNull(result.columnCount)
    }

    @Test
    fun doublePageInLandscapeFixedLayoutProducesSpreadAlways() {
        val result = FormattingPreferences(doublePageSpread = true).toEpubPreferences(
            isLandscape = true,
            isFixedLayout = true,
        )
        assertEquals(Spread.ALWAYS, result.spread)
        assertNull(result.columnCount)
    }

    @Test
    fun doublePageOffInLandscapeFixedLayoutProducesSpreadNever() {
        val result = FormattingPreferences(doublePageSpread = false).toEpubPreferences(
            isLandscape = true,
            isFixedLayout = true,
        )
        assertEquals(Spread.NEVER, result.spread)
    }

}
