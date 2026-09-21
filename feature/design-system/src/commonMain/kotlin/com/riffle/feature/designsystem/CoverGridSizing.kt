package com.riffle.feature.designsystem

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.riffle.feature.library.CoverGridLayout

/**
 * Whether the covers in the surrounding grid/row are square (audio artwork) rather than 2:3
 * (book jackets) — ADR 0035. Provided by whichever screen knows what it is listing; an individual
 * audiobook-only item overrides it to square regardless.
 *
 * One declaration for both hosts. `app/.../LibraryItemsScreen.kt` used to declare it privately and
 * iOS simply had no equivalent, so the browse tabs that are entirely audio rendered letterboxed
 * 2:3 tiles on iPhone.
 */
val LocalCoversAreSquare = compositionLocalOf { false }

/** Aspect ratio for a cover box: 1:1 for audio artwork, 2:3 for a book jacket (ADR 0035). */
fun coverAspectRatio(square: Boolean): Float = if (square) 1f else 2f / 3f

/**
 * Minimum cell size for `GridCells.Adaptive` full-page cover grids, indexed on the current window
 * width per ADR 0019 and scaled by the user's persisted pinch multiplier.
 *
 * This replaces the two copies that disagreed about where the width came from: Android's
 * `coverGridMinCellSize()` read `LocalConfiguration.screenWidthDp` and iOS's `coverGridMinCell()`
 * read `LocalWindowInfo.containerSize`. Both describe the app window, and the multiplatform one is
 * the only reading available in `commonMain`, so that is the one that survives. The breakpoint and
 * the clamp themselves have always been in [CoverGridLayout].
 */
@Composable
@ReadOnlyComposable
fun coverGridMinCell(): Dp =
    CoverGridLayout.minCellSizeDp(windowWidthDp().value, LocalCoverGridScale.current).dp

/**
 * As [coverGridMinCell] but for the denser home-shelf / To Read grids — same Expanded breakpoint,
 * smaller cells, so a row shows ~4 covers on a phone instead of 3.
 */
@Composable
@ReadOnlyComposable
fun shelfCoverMinCell(): Dp =
    CoverGridLayout.shelfMinCellSizeDp(windowWidthDp().value, LocalCoverGridScale.current).dp
