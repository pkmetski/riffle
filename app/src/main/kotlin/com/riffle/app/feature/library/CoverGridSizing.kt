package com.riffle.app.feature.library

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

private val PhoneCoverMinCellSize = 112.dp
private val ExpandedCoverMinCellSize = 160.dp

/**
 * Minimum cell size for `GridCells.Adaptive` cover grids, indexed on the current
 * window width per ADR 0019: Compact and Medium use the phone size; Expanded
 * (≥ 840dp) uses a larger cell so tablet covers feel browseable instead of
 * dense phone-sized thumbnails. Re-evaluated on configuration change.
 */
@Composable
@ReadOnlyComposable
fun coverGridMinCellSize(): Dp {
    val widthDp = LocalConfiguration.current.screenWidthDp
    return if (widthDp >= 840) ExpandedCoverMinCellSize else PhoneCoverMinCellSize
}
