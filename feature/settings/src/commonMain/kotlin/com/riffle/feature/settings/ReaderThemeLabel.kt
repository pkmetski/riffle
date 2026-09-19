package com.riffle.feature.settings

import com.riffle.core.domain.ReaderTheme

/**
 * The untranslated, human-readable name of a reader theme.
 *
 * Shared by every settings summary that has to render a theme without a Composable scope (and
 * therefore without `stringResource`). Android's localized counterpart is
 * `ReaderTheme.localizedLabel()`; this one is the fallback/summary text and the string the
 * JVM + iOS unit tests pin.
 */
fun ReaderTheme.label(): String = when (this) {
    ReaderTheme.Light -> "Light"
    ReaderTheme.Dark -> "Dark"
    ReaderTheme.DarkDim -> "Dim"
    ReaderTheme.Sepia -> "Sepia"
    ReaderTheme.Auto -> "Auto"
}
