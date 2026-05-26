@file:OptIn(org.readium.r2.shared.ExperimentalReadiumApi::class)

package com.riffle.app.feature.reader

import com.riffle.core.domain.FormattingPreferences
import com.riffle.core.domain.ReaderFontFamily
import com.riffle.core.domain.ReaderOrientation
import com.riffle.core.domain.ReaderTheme
import org.readium.r2.navigator.epub.EpubPreferences
import org.readium.r2.navigator.preferences.ColumnCount
import org.readium.r2.navigator.preferences.FontFamily
import org.readium.r2.navigator.preferences.Spread
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
        // lineHeight only takes effect when publisherStyles is off
        publisherStyles = false,
        lineHeight = lineSpacing.toDouble(),
        pageMargins = margins.toDouble(),
        scroll = orientation == ReaderOrientation.Vertical,
        // Reflowable: CSS column count controls two-column text layout.
        // Fixed-layout: spread controls two-page side-by-side rendering; column count is ignored.
        columnCount = if (!isFixedLayout && isDoublePage) ColumnCount.TWO else ColumnCount.ONE,
        spread = if (isFixedLayout) (if (isDoublePage) Spread.ALWAYS else Spread.NEVER) else null,
    )
}
