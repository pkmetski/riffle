package com.riffle.app.feature.reader

import androidx.compose.ui.graphics.Color
import com.riffle.core.domain.ReaderTheme

// Single source of truth for what each reader theme paints. Readium owns the actual page
// rendering (see FormattingPreferencesMapper.toEpubPreferences → Theme.LIGHT/DARK/SEPIA),
// but the app surfaces this palette in three places that must stay in lock-step with what
// Readium draws: the formatting-panel theme swatches, the chapter-rail overlay backdrop,
// and the DarkDim foreground override the mapper hands back to Readium.
//
// Values mirror the `--RS__backgroundColor` / `--RS__textColor` declarations Readium ships
// in its CSS (assets/readium/readium-css/ReadiumCSS-after.css inside readium-navigator.aar,
// rules `readium-night-on` and `readium-sepia-on`; default values are from
// ReadiumCSS-before.css). Update this table if you bump Readium and the colours move.
data class ReaderThemePalette(
    val background: Color,
    val foreground: Color,
)

// Riffle's "dark dim" mode reuses Readium's Theme.DARK background but overrides the body
// text colour to a softer grey. The override is passed to Readium via
// EpubPreferences.textColor in FormattingPreferencesMapper.
internal val DARK_DIM_TEXT: Color = Color(0xFFAAAAAA)

val ReaderTheme.palette: ReaderThemePalette
    get() = when (this) {
        ReaderTheme.Light -> ReaderThemePalette(
            background = Color(0xFFFFFFFF),
            foreground = Color(0xFF121212),
        )
        ReaderTheme.Dark -> ReaderThemePalette(
            background = Color(0xFF000000),
            foreground = Color(0xFFFEFEFE),
        )
        ReaderTheme.DarkDim -> ReaderThemePalette(
            background = Color(0xFF000000),
            foreground = DARK_DIM_TEXT,
        )
        ReaderTheme.Sepia -> ReaderThemePalette(
            background = Color(0xFFFAF4E8),
            foreground = Color(0xFF121212),
        )
        // Defensive: Auto must be resolved to a concrete theme via
        // FormattingPreferences.withResolvedTheme() before reaching this palette.
        // We fall back to Light so a missed resolution doesn't crash the reader,
        // but every production call site should resolve first.
        ReaderTheme.Auto -> ReaderThemePalette(
            background = Color(0xFFFFFFFF),
            foreground = Color(0xFF121212),
        )
    }
