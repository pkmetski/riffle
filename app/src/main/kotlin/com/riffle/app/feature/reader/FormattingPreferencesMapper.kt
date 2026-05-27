@file:OptIn(org.readium.r2.shared.ExperimentalReadiumApi::class)

package com.riffle.app.feature.reader

import com.riffle.core.domain.FormattingPreferences
import com.riffle.core.domain.ReaderFontFamily
import com.riffle.core.domain.ReaderOrientation
import com.riffle.core.domain.ReaderTheme
import org.readium.r2.navigator.epub.EpubNavigatorFragment
import org.readium.r2.navigator.epub.EpubPreferences
import org.readium.r2.navigator.epub.css.ColCount
import org.readium.r2.navigator.epub.css.RsProperties
import org.readium.r2.navigator.preferences.FontFamily
import org.readium.r2.navigator.preferences.Spread
import org.readium.r2.navigator.preferences.TextAlign
import org.readium.r2.navigator.preferences.Theme

fun FormattingPreferences.toEpubPreferences(
    isLandscape: Boolean = false,
    isFixedLayout: Boolean = false,
): EpubPreferences {
    val isDoublePage = orientation != ReaderOrientation.Vertical && doublePageSpread && isLandscape
    return EpubPreferences(
        fontSize = fontSize.toDouble(),
        theme = when (theme) {
            ReaderTheme.Light -> Theme.LIGHT
            ReaderTheme.Dark -> Theme.DARK
            ReaderTheme.Sepia -> Theme.SEPIA
        },
        fontFamily = when (fontFamily) {
            ReaderFontFamily.Serif -> FontFamily("serif")
            ReaderFontFamily.SansSerif -> FontFamily("sans-serif")
            ReaderFontFamily.Monospace -> FontFamily("monospace")
            ReaderFontFamily.Literata -> FontFamily("Literata")
            ReaderFontFamily.Merriweather -> FontFamily("Merriweather")
            ReaderFontFamily.OpenDyslexic -> FontFamily("OpenDyslexic")
        },
        textAlign = if (justifyText) TextAlign.JUSTIFY else TextAlign.START,
        // lineHeight only takes effect when publisherStyles is off
        publisherStyles = false,
        lineHeight = lineSpacing.toDouble(),
        pageMargins = margins.toDouble(),
        scroll = orientation == ReaderOrientation.Vertical,
        // Fixed-layout: spread controls two-page side-by-side rendering.
        // Reflowable: column count is set via RS properties in toFragmentConfiguration().
        spread = if (isFixedLayout) (if (isDoublePage) Spread.ALWAYS else Spread.NEVER) else null,
    )
}

fun FormattingPreferences.toFragmentConfiguration(
    isLandscape: Boolean = false,
    isFixedLayout: Boolean = false,
): EpubNavigatorFragment.Configuration {
    // --RS__colCount is injected as an unconditional inline style on <html> at page-load
    // time, bypassing Readium's 60em media-query threshold that --USER__colCount relies on.
    // --RS__colWidth must be "auto" to remove the default 45em minimum which would otherwise
    // prevent two columns from fitting in a phone-width viewport.
    val isDoublePage = !isFixedLayout &&
        orientation != ReaderOrientation.Vertical &&
        doublePageSpread &&
        isLandscape
    return EpubNavigatorFragment.Configuration(
        readiumCssRsProperties = if (isDoublePage) {
            RsProperties(
                colCount = ColCount.TWO,
                overrides = mapOf("--RS__colWidth" to "auto"),
            )
        } else {
            RsProperties()
        },
    )
}
