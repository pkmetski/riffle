package com.riffle.feature.source.ui

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import com.riffle.feature.library.CoverGridLayout

/**
 * Pinch-to-zoom for cover grids — the one implementation both hosts render.
 *
 * Only two-finger gestures are claimed (and only those events consumed), so single-finger
 * scrolling on the underlying lazy grid is untouched. Reports the new scale, clamped by
 * [CoverGridLayout.clampScale], via [onScaleChange]; the caller persists it.
 *
 * [scale] is passed in rather than read off a `CompositionLocal` on purpose: each host publishes
 * the user's multiplier through its own local (`com.riffle.app.feature.library.LocalCoverGridScale`
 * on Android, `com.riffle.shared.library.LocalCoverGridScale` on iOS), and taking the value as a
 * parameter is what lets the gesture itself live in one place instead of being written twice —
 * which is what it was until this module got it.
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
