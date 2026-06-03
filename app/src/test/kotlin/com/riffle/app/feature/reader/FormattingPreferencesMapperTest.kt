@file:OptIn(org.readium.r2.shared.ExperimentalReadiumApi::class)

package com.riffle.app.feature.reader

import com.riffle.core.domain.FormattingPreferences
import com.riffle.core.domain.ReaderOrientation
import org.junit.Assert.assertEquals
import org.junit.Test
import org.readium.r2.navigator.epub.css.ColCount
import org.readium.r2.navigator.epub.css.RsProperties

class FragmentConfigurationMapperTest {

    @Test
    fun doublePageInLandscapeReflowableSetsRsColCountTwo() {
        val result = FormattingPreferences(doublePageSpread = true).toFragmentConfiguration(
            isLandscape = true,
            isFixedLayout = false,
        )
        assertEquals(ColCount.TWO, result.readiumCssRsProperties.colCount)
        assertEquals("auto", result.readiumCssRsProperties.overrides["--RS__colWidth"])
    }

    @Test
    fun doublePageInPortraitReflowablePinsSingleColumn() {
        // Double-page only triggers two columns in landscape; portrait stays single-column.
        val result = FormattingPreferences(doublePageSpread = true).toFragmentConfiguration(
            isLandscape = false,
            isFixedLayout = false,
        )
        assertEquals(ColCount.ONE, result.readiumCssRsProperties.colCount)
    }

    @Test
    fun doublePageOffInLandscapeReflowablePinsSingleColumn() {
        val result = FormattingPreferences(doublePageSpread = false).toFragmentConfiguration(
            isLandscape = true,
            isFixedLayout = false,
        )
        assertEquals(ColCount.ONE, result.readiumCssRsProperties.colCount)
    }

    // Regression: Readium 3.3.0 changed the reflowable default so a phone-width viewport rendered
    // TWO columns, and its decoration renderer mispositions the readaloud highlight in a multi-column
    // layout — so the synced highlight silently vanished (e.g. The Martian). We pin reflowable
    // single-page rendering to ONE column; this test fails if that default is ever lost (e.g. a future
    // engine upgrade reintroduces multi-column), which would bring the missing-highlight bug back.
    @Test
    fun reflowableSinglePagePinsOneColumnSoTheReadaloudHighlightStaysVisible() {
        val result = FormattingPreferences().toFragmentConfiguration(
            isLandscape = false,
            isFixedLayout = false,
        )
        assertEquals(ColCount.ONE, result.readiumCssRsProperties.colCount)
    }

    @Test
    fun verticalOrientationUsesDefaultColumns() {
        val result = FormattingPreferences(
            orientation = ReaderOrientation.Vertical,
            doublePageSpread = true,
        ).toFragmentConfiguration(isLandscape = true, isFixedLayout = false)
        assertEquals(null, result.readiumCssRsProperties.colCount)
    }

    @Test
    fun doublePageInLandscapeFixedLayoutUsesDefaultColumns() {
        val result = FormattingPreferences(doublePageSpread = true).toFragmentConfiguration(
            isLandscape = true,
            isFixedLayout = true,
        )
        assertEquals(null, result.readiumCssRsProperties.colCount)
    }

    // Regression: cutout/punch-hole devices in scroll mode used to show a status-bar-height
    // band of page background at the top because R2EpubPageFragment reads displayCutout
    // insets directly from decorView, bypassing our inset consumption at the AndroidView root.
    // Compose owns inset handling for the reader, so Readium must not add its own padding.
    @Test
    fun fragmentConfigurationDisablesReadiumInsetsPadding() {
        val result = FormattingPreferences().toFragmentConfiguration(
            isLandscape = false,
            isFixedLayout = false,
        )
        assertEquals(false, result.shouldApplyInsetsPadding)
    }

}
