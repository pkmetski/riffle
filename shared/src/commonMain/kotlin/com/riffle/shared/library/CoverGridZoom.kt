package com.riffle.shared.library

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import com.riffle.feature.library.CoverGridLayout

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
        Box(modifier = modifier.pinchCoverZoom(onScaleChange), content = content)
    }
}

/**
 * Pinch-to-zoom for cover grids. Only two-finger gestures are claimed (and only those events
 * consumed), so single-finger scrolling on the underlying lazy grid is untouched. Reports the
 * new, clamped scale via [onScaleChange]; the caller persists it.
 */
@Composable
private fun Modifier.pinchCoverZoom(onScaleChange: (Float) -> Unit): Modifier {
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
                        onChange.value(CoverGridLayout.clampScale(scale.value * zoom))
                        event.changes.forEach { if (it.pressed) it.consume() }
                    }
                }
            } while (event.changes.any { it.pressed })
        }
    }
}
