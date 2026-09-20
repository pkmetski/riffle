package com.riffle.feature.source.ui

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import com.riffle.feature.library.CoverGridLayout

/**
 * The user's persisted cover-grid zoom multiplier (1.0 = shipped defaults), provided once at each
 * host's library screen root. Every cell-size reader scales off it, so a pinch anywhere reflows
 * every cover grid on the screen consistently.
 *
 * One declaration for both hosts: it and [pinchCoverZoom] are two halves of the same contract, and
 * while they were declared twice the two platforms could disagree about the default without
 * anything failing.
 */
val LocalCoverGridScale = compositionLocalOf { 1f }

/**
 * Pinch-to-zoom for cover grids — the one implementation both hosts render.
 *
 * Only two-finger gestures are claimed (and only those events consumed), so single-finger
 * scrolling on the underlying lazy grid is untouched. Reports the new scale, clamped by
 * [CoverGridLayout.clampScale], via [onScaleChange]; the caller persists it.
 *
 * [scale] is a parameter rather than a read of [LocalCoverGridScale] so the gesture has no
 * ambient dependency and can be driven directly by a test; callers pass
 * `LocalCoverGridScale.current`.
 */
@Composable
fun Modifier.pinchCoverZoom(scale: Float, onScaleChange: (Float) -> Unit): Modifier {
    val currentScale = rememberUpdatedState(scale)
    val onChange = rememberUpdatedState(onScaleChange)
    return this.pointerInput(Unit) {
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false)
            do {
                val event = awaitPointerEvent()
                if (event.changes.count { it.pressed } >= 2) {
                    val zoom = event.calculateZoom()
                    if (zoom != 1f) {
                        onChange.value(CoverGridLayout.clampScale(currentScale.value * zoom))
                        event.changes.forEach { if (it.pressed) it.consume() }
                    }
                }
            } while (event.changes.any { it.pressed })
        }
    }
}
