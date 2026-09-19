package com.riffle.app.feature.readersettings

import androidx.compose.ui.graphics.Color
import com.riffle.core.domain.FormattingPreferences
import com.riffle.core.domain.ReaderTheme
import com.riffle.feature.reader.DARK_DIM_TEXT_ARGB
import com.riffle.feature.reader.argbPalette
import com.riffle.feature.reader.swatchBackdropArgb

// Compose façade over the shared reader-theme colour table in
// `com.riffle.feature.reader.ReaderThemeArgbPalette`. The table itself lives in commonMain so
// iOS renders the same paper as Android; this file only widens the 0xAARRGGBB longs into
// Compose `Color`s for the Android UI. Add or change a colour in the shared table, never here.
data class ReaderThemePalette(
    val background: Color,
    val foreground: Color,
)

// Riffle's "dark dim" mode reuses Readium's Theme.DARK background but overrides the body
// text colour to a softer grey. The override is passed to Readium via
// EpubPreferences.textColor in FormattingPreferencesMapper.
internal val DARK_DIM_TEXT: Color = Color(DARK_DIM_TEXT_ARGB)

val ReaderTheme.palette: ReaderThemePalette
    get() = argbPalette.let { ReaderThemePalette(Color(it.background), Color(it.foreground)) }

/**
 * The opaque backdrop colour every highlight-colour swatch must composite over so the alpha-0x80
 * highlight preview matches what actually lands on a book page. Callers (reader popup, Readaloud
 * settings, Cadence settings) must paint this behind the swatch — otherwise the app-theme
 * `Surface` colour underlies the swatch and the preview looks nothing like the highlight (e.g. a
 * dark-app / light-reader combo makes yellow look muddy in the picker, bright yellow in the book).
 */
val FormattingPreferences.swatchBackdropColor: Color
    get() = Color(swatchBackdropArgb)
