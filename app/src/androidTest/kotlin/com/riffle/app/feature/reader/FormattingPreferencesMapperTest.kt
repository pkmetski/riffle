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
        assertEquals(null, result.textColor)
    }

    @Test
    fun darkDimThemeMapsToDARKWithDimmedTextColor() {
        val result = FormattingPreferences(theme = ReaderTheme.DarkDim).toEpubPreferences()
        assertEquals(Theme.DARK, result.theme)
        // Slightly dimmer than pure white — see DARK_DIM_TEXT_COLOR in FormattingPreferencesMapper.
        assertEquals(0xFFAAAAAA.toInt(), result.textColor?.int)
    }

    @Test
    fun sepiaThemeMapsToSEPIA() {
        val result = FormattingPreferences(theme = ReaderTheme.Sepia).toEpubPreferences()
        assertEquals(Theme.SEPIA, result.theme)
    }

    @Test
    fun autoThemeMapsToLIGHTAsDefensiveFallback() {
        // Reader VM resolves Auto before calling the mapper; this verifies the
        // fallback path the mapper provides for exhaustiveness.
        val result = FormattingPreferences(theme = ReaderTheme.Auto).toEpubPreferences()
        assertEquals(Theme.LIGHT, result.theme)
    }

    // Original is the default ReaderFontFamily; mapping it to null leaves --USER__fontFamily
    // unset on :root so the publisher's typography is preserved on books the user hasn't
    // customized. See typographyOverrideCss() — the gate `:root[style*="--USER__fontFamily"]`
    // requires the variable to be present for any override to apply.
    @Test
    fun defaultOriginalFontFamilyMapsToNullSoPublisherTypographyIsPreserved() {
        val result = FormattingPreferences(fontFamily = ReaderFontFamily.Original).toEpubPreferences()
        assertNull(result.fontFamily)
    }

    // Regression: the generic "Serif" chip must force a real serif face, not passthrough. The
    // previous behaviour (Serif → null → publisher font) misled users who picked "Serif" to
    // force serif letterforms and instead got whatever the publisher shipped.
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
    fun customLineSpacingMapsToLineHeight() {
        val result = FormattingPreferences(lineSpacing = 1.8f).toEpubPreferences()
        assertEquals(1.8, result.lineHeight!!, 0.001)
    }

    // Default lineSpacing must map to null so Readium leaves --USER__lineHeight unset on :root,
    // which keeps the publisher's line-height intact on books the user hasn't customized.
    // Without this, every book would have its publisher line-height overridden the moment our
    // typography-override stylesheet is injected.
    @Test
    fun defaultLineSpacingMapsToNullLineHeight() {
        val result = FormattingPreferences().toEpubPreferences()
        assertNull(result.lineHeight)
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

    // --- Fixed-layout spread mapping ---

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
        assertNull(result.columnCount)
    }

    @Test
    fun reflowableEpubNeverSetsColumnCount() {
        assertNull(FormattingPreferences(doublePageSpread = true).toEpubPreferences(isLandscape = true).columnCount)
        assertNull(FormattingPreferences(doublePageSpread = false).toEpubPreferences(isLandscape = true).columnCount)
    }

    @Test
    fun justifyTextTrueMapsToTextAlignJustify() {
        val result = FormattingPreferences(justifyText = true).toEpubPreferences()
        assertEquals(org.readium.r2.navigator.preferences.TextAlign.JUSTIFY, result.textAlign)
    }

    // Same null-gating rationale as defaultLineSpacingMapsToNullLineHeight: the default
    // (unjustified) state must leave --USER__textAlign unset so the publisher's text alignment
    // is preserved.
    @Test
    fun justifyTextFalseMapsToNullTextAlign() {
        val result = FormattingPreferences(justifyText = false).toEpubPreferences()
        assertNull(result.textAlign)
    }

}
