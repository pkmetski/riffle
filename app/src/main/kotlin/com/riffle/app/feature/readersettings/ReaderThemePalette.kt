package com.riffle.app.feature.readersettings

import androidx.compose.ui.graphics.Color
import com.riffle.core.domain.FormattingPreferences
import com.riffle.core.domain.ReaderTheme
import com.riffle.feature.reader.DARK_DIM_TEXT_ARGB
import com.riffle.feature.reader.ui.readerPalette
import com.riffle.feature.reader.ui.readerSwatchBackdropColor

// Android-side aliases for the shared Compose façade in `:feature:reader-ui`, which itself widens
// the 0xAARRGGBB table in `com.riffle.feature.reader.ReaderThemeArgbPalette`. Keeping the names
// `palette` / `swatchBackdropColor` means the ~40 Android call sites don't move; the widening now
// happens in exactly one place that iOS renders too. Add or change a colour in the shared ARGB
// table, never here.
typealias ReaderThemePalette = com.riffle.feature.reader.ui.ReaderThemePalette

// Riffle's "dark dim" mode reuses Readium's Theme.DARK background but overrides the body
// text colour to a softer grey. The override is passed to Readium via
// EpubPreferences.textColor in FormattingPreferencesMapper.
internal val DARK_DIM_TEXT: Color = Color(DARK_DIM_TEXT_ARGB)

val ReaderTheme.palette: ReaderThemePalette
    get() = readerPalette

/**
 * The opaque backdrop colour every highlight-colour swatch must composite over so the alpha-0x80
 * highlight preview matches what actually lands on a book page. Callers (reader popup, Readaloud
 * settings, Cadence settings) must paint this behind the swatch — otherwise the app-theme
 * `Surface` colour underlies the swatch and the preview looks nothing like the highlight (e.g. a
 * dark-app / light-reader combo makes yellow look muddy in the picker, bright yellow in the book).
 */
val FormattingPreferences.swatchBackdropColor: Color
    get() = readerSwatchBackdropColor
