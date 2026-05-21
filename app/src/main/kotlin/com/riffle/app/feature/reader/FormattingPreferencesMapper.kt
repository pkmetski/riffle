package com.riffle.app.feature.reader

import com.riffle.core.domain.FormattingPreferences
import com.riffle.core.domain.ReaderFontFamily
import com.riffle.core.domain.ReaderOrientation
import com.riffle.core.domain.ReaderTheme
import org.readium.r2.navigator.epub.EpubPreferences
import org.readium.r2.navigator.preferences.FontFamily
import org.readium.r2.navigator.preferences.Theme

fun FormattingPreferences.toEpubPreferences(): EpubPreferences = EpubPreferences(
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
    lineHeight = lineSpacing.toDouble(),
    pageMargins = margins.toDouble(),
    scroll = orientation == ReaderOrientation.Scroll,
)
