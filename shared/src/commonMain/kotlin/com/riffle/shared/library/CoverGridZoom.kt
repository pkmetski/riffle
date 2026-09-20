package com.riffle.shared.library

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier
import com.riffle.feature.library.CoverGridLayout
import com.riffle.feature.source.ui.pinchCoverZoom

/**
 * The user's persisted cover-grid zoom multiplier (1.0 = shipped defaults), provided once at the
 * library screen root. Mirrors Android's `LocalCoverGridScale`; both read their clamp and their
 * cell arithmetic from [CoverGridLayout] so a pinch means the same thing on either platform.
 *
 * `LibraryItemsViewModel.coverGridScale` / `setCoverGridScale` and the per-device
 * `CoverGridScaleDao` behind them have existed on iOS all along — the grids simply passed a
 * hardcoded `1f` and nothing ever called the setter (#1071 §15.6).
 */
internal val LocalCoverGridScale = compositionLocalOf { 1f }

/**
 * A [Box] that publishes [scale] to every cover grid inside it and turns a two-finger pinch
 * anywhere within it into a density change, so all of a library's grids reflow together.
 */
@Composable
internal fun CoverGridZoomBox(
    scale: Float,
    onScaleChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    CompositionLocalProvider(LocalCoverGridScale provides scale) {
        Box(modifier = modifier.pinchCoverZoom(scale, onScaleChange), content = content)
    }
}
