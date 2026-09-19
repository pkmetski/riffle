package com.riffle.feature.reader

import com.riffle.core.domain.FormattingPreferences
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
//
// Colours are plain 0xAARRGGBB longs rather than a UI-toolkit colour type so this table can
// live in shared code that both Android (Compose `Color`) and iOS consume. `ReaderThemePalette`
// in `app` is the thin Compose wrapper over these values.
data class ReaderThemeArgbPalette(
    val background: Long,
    val foreground: Long,
)

// Riffle's "dark dim" mode reuses Readium's Theme.DARK background but overrides the body
// text colour to a softer grey. The override is passed to Readium via
// EpubPreferences.textColor in FormattingPreferencesMapper.
const val DARK_DIM_TEXT_ARGB: Long = 0xFFAAAAAA

val ReaderTheme.argbPalette: ReaderThemeArgbPalette
    get() = when (this) {
        ReaderTheme.Light -> ReaderThemeArgbPalette(
            background = 0xFFFFFFFF,
            foreground = 0xFF121212,
        )
        ReaderTheme.Dark -> ReaderThemeArgbPalette(
            background = 0xFF000000,
            foreground = 0xFFFEFEFE,
        )
        ReaderTheme.DarkDim -> ReaderThemeArgbPalette(
            background = 0xFF000000,
            foreground = DARK_DIM_TEXT_ARGB,
        )
        ReaderTheme.Sepia -> ReaderThemeArgbPalette(
            background = 0xFFFAF4E8,
            foreground = 0xFF121212,
        )
        // Defensive: Auto must be resolved to a concrete theme via
        // FormattingPreferences.withResolvedTheme() before reaching this palette.
        // Fall back to Light so a missed resolution doesn't crash the reader; every
        // production call site should resolve first. Delegating to Light keeps the
        // two in lock-step if Light's colours ever move.
        ReaderTheme.Auto -> ReaderTheme.Light.argbPalette
    }

/**
 * The opaque backdrop colour every highlight-colour swatch must composite over so the alpha-0x80
 * highlight preview matches what actually lands on a book page. Callers (reader popup, Readaloud
 * settings, Cadence settings) must paint this behind the swatch — otherwise the app-theme
 * `Surface` colour underlies the swatch and the preview looks nothing like the highlight (e.g. a
 * dark-app / light-reader combo makes yellow look muddy in the picker, bright yellow in the book).
 */
val FormattingPreferences.swatchBackdropArgb: Long
    get() = theme.argbPalette.background
