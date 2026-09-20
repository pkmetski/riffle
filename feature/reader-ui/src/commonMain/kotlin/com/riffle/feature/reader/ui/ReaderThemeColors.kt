package com.riffle.feature.reader.ui

import androidx.compose.ui.graphics.Color
import com.riffle.core.domain.FormattingPreferences
import com.riffle.core.domain.ReaderTheme
import com.riffle.feature.reader.argbPalette
import com.riffle.feature.reader.swatchBackdropArgb

/**
 * Compose façade over the shared reader-theme colour table in
 * `com.riffle.feature.reader.ReaderThemeArgbPalette`. The table itself lives in `feature:reader`'s
 * commonMain so iOS renders the same paper as Android; this file only widens the 0xAARRGGBB longs
 * into Compose `Color`s. Add or change a colour in the shared table, never here.
 *
 * It lives in this android+ios module rather than in `:app` so both hosts widen the same way —
 * `:app`'s `com.riffle.app.feature.readersettings.palette` now delegates here.
 */
data class ReaderThemePalette(
    val background: Color,
    val foreground: Color,
)

val ReaderTheme.readerPalette: ReaderThemePalette
    get() = argbPalette.let { ReaderThemePalette(Color(it.background), Color(it.foreground)) }

/**
 * The opaque backdrop colour every highlight-colour swatch must composite over so the alpha-0x80
 * highlight preview matches what actually lands on a book page.
 */
val FormattingPreferences.readerSwatchBackdropColor: Color
    get() = Color(swatchBackdropArgb)

/**
 * Reader-theme-paired label colour: page foreground at reduced alpha so the labels read as a
 * continuation of the page, not chrome — and don't compete with actual body text. Per-theme alpha
 * because the same alpha across themes reads as different "loudness" depending on the
 * foreground/background contrast.
 */
fun readerThemeLabelColor(theme: ReaderTheme): Color {
    val alpha = when (theme) {
        ReaderTheme.Light -> 0.65f
        ReaderTheme.Dark -> 0.65f
        ReaderTheme.DarkDim -> 0.85f
        ReaderTheme.Sepia -> 0.70f
        // Auto resolves upstream; treat as Light if it slips through.
        ReaderTheme.Auto -> 0.65f
    }
    return theme.readerPalette.foreground.copy(alpha = alpha)
}
