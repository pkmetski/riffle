package com.riffle.app.feature.library

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.riffle.feature.library.CoverGridLayout

// Base sizes at scale 1.0, the size-class breakpoint and the pinch clamp live in
// [CoverGridLayout] (feature:library/commonMain) so the Compose-Multiplatform
// grids the iOS app renders reach the same numbers. Phone matches the browse
// tabs (~3 per row); tablet packs a little tighter (~5-6) so the wider screen
// isn't dominated by huge covers. The pinch gesture itself is
// [com.riffle.feature.source.ui.pinchCoverZoom] — one implementation, both hosts.

/**
 * The user's persisted cover-grid zoom multiplier (1.0 = shipped defaults).
 * Provided once at the library screen root; every [coverGridMinCellSize] /
 * [shelfCoverMinCellSize] reader scales off it, so a pinch anywhere reflows
 * every cover grid consistently.
 */
val LocalCoverGridScale = compositionLocalOf { 1f }

/**
 * Minimum cell size for `GridCells.Adaptive` cover grids, indexed on the current
 * window width per ADR 0019: Compact and Medium use the phone size; Expanded
 * (≥ 840dp) uses a larger cell so tablet covers feel browseable instead of
 * dense phone-sized thumbnails. Re-evaluated on configuration change and scaled
 * by the user's pinch zoom.
 */
@Composable
@ReadOnlyComposable
fun coverGridMinCellSize(): Dp {
    val widthDp = LocalConfiguration.current.screenWidthDp
    return CoverGridLayout.minCellSizeDp(widthDp.toFloat(), LocalCoverGridScale.current).dp
}

/**
 * Minimum cell size for the denser home-shelf / To Read cover grids. Same
 * Expanded (≥ 840dp) breakpoint as [coverGridMinCellSize] but smaller cells so
 * the row shows ~4 covers on a phone and ~5-6 on a tablet instead of 3. Scaled
 * by the user's pinch zoom.
 */
@Composable
@ReadOnlyComposable
fun shelfCoverMinCellSize(): Dp {
    val widthDp = LocalConfiguration.current.screenWidthDp
    return CoverGridLayout.shelfMinCellSizeDp(widthDp.toFloat(), LocalCoverGridScale.current).dp
}
