package com.riffle.app.feature.library

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

private val PhoneCoverMinCellSize = 112.dp
private val ExpandedCoverMinCellSize = 160.dp

// Base sizes at scale 1.0; the user's pinch zoom multiplies them via
// [LocalCoverGridScale]. Phone matches the browse tabs (~3 per row); tablet packs
// a little tighter (~5-6) so the wider screen isn't dominated by huge covers.
private val PhoneShelfCoverMinCellSize = 112.dp
private val ExpandedShelfCoverMinCellSize = 140.dp

/** Lower/upper bounds for the user's pinch-to-zoom multiplier. */
const val MIN_COVER_SCALE = 0.7f
const val MAX_COVER_SCALE = 1.6f

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
    val base = if (widthDp >= 840) ExpandedCoverMinCellSize else PhoneCoverMinCellSize
    return base * LocalCoverGridScale.current
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
    val base = if (widthDp >= 840) ExpandedShelfCoverMinCellSize else PhoneShelfCoverMinCellSize
    return base * LocalCoverGridScale.current
}

/**
 * Pinch-to-zoom for cover grids. Only two-finger gestures are claimed (and only
 * those events consumed), so single-finger scrolling on the underlying lazy grid
 * is untouched. Reports the new, clamped [LocalCoverGridScale] value via
 * [onScaleChange]; the caller persists it.
 */
@Composable
fun Modifier.pinchCoverZoom(onScaleChange: (Float) -> Unit): Modifier {
    val scale = rememberUpdatedState(LocalCoverGridScale.current)
    val onChange = rememberUpdatedState(onScaleChange)
    return this.pointerInput(Unit) {
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false)
            do {
                val event = awaitPointerEvent()
                if (event.changes.count { it.pressed } >= 2) {
                    val zoom = event.calculateZoom()
                    if (zoom != 1f) {
                        val next = (scale.value * zoom).coerceIn(MIN_COVER_SCALE, MAX_COVER_SCALE)
                        onChange.value(next)
                        event.changes.forEach { if (it.pressed) it.consume() }
                    }
                }
            } while (event.changes.any { it.pressed })
        }
    }
}
