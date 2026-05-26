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
    fun doublePageInPortraitReflowableUsesDefaultRsProperties() {
        val result = FormattingPreferences(doublePageSpread = true).toFragmentConfiguration(
            isLandscape = false,
            isFixedLayout = false,
        )
        assertEquals(RsProperties(), result.readiumCssRsProperties)
    }

    @Test
    fun doublePageOffInLandscapeReflowableUsesDefaultRsProperties() {
        val result = FormattingPreferences(doublePageSpread = false).toFragmentConfiguration(
            isLandscape = true,
            isFixedLayout = false,
        )
        assertEquals(RsProperties(), result.readiumCssRsProperties)
    }

    @Test
    fun verticalOrientationUsesDefaultRsProperties() {
        val result = FormattingPreferences(
            orientation = ReaderOrientation.Vertical,
            doublePageSpread = true,
        ).toFragmentConfiguration(isLandscape = true, isFixedLayout = false)
        assertEquals(RsProperties(), result.readiumCssRsProperties)
    }

    @Test
    fun doublePageInLandscapeFixedLayoutUsesDefaultRsProperties() {
        val result = FormattingPreferences(doublePageSpread = true).toFragmentConfiguration(
            isLandscape = true,
            isFixedLayout = true,
        )
        assertEquals(RsProperties(), result.readiumCssRsProperties)
    }

}
