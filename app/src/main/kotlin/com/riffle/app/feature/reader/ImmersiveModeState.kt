package com.riffle.app.feature.reader

import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.systemBars
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalDensity
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

@Stable
class ImmersiveModeState(
    private val controller: WindowInsetsControllerCompat,
) {
    var isImmersive by mutableStateOf(false)
        internal set

    fun toggle() {
        if (isImmersive) show() else hide()
    }

    internal fun hide() {
        isImmersive = true
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_DEFAULT
        controller.hide(WindowInsetsCompat.Type.systemBars())
    }

    internal fun show() {
        isImmersive = false
        controller.show(WindowInsetsCompat.Type.systemBars())
    }
}

@Composable
fun rememberImmersiveModeState(): ImmersiveModeState {
    val window = checkNotNull(LocalActivity.current).window
    val controller = remember(window) { WindowInsetsControllerCompat(window, window.decorView) }
    val state = remember(controller) { ImmersiveModeState(controller) }

    // Sync isImmersive with actual system bar visibility.
    // When BEHAVIOR_DEFAULT is set, an edge-swipe permanently restores the bars, and
    // Compose's WindowInsets.systemBars reflects the change.
    //
    // We track prevTopInset to distinguish two cases:
    //   - Bars animating OUT (topInset: 56→40→20→0): prevTopInset never reaches 0 first,
    //     so we never reset isImmersive mid-animation.
    //   - Bars restored by edge-swipe (topInset: 0→20→...): prevTopInset WAS 0,
    //     so the first non-zero value triggers the reset.
    val density = LocalDensity.current
    val topInset = WindowInsets.systemBars.getTop(density)
    val prevTopInset = remember { mutableStateOf(topInset) }
    LaunchedEffect(topInset) {
        val wasHidden = prevTopInset.value == 0
        prevTopInset.value = topInset
        if (state.isImmersive && topInset > 0 && wasHidden) state.isImmersive = false
    }

    // Always restore system bars when the reader screen leaves the composition.
    DisposableEffect(state) {
        onDispose { state.show() }
    }

    return state
}
