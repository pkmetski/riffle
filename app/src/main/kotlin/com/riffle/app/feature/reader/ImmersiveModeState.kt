package com.riffle.app.feature.reader

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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.fragment.app.FragmentActivity

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
    val window = (LocalContext.current as FragmentActivity).window
    val controller = remember(window) { WindowInsetsControllerCompat(window, window.decorView) }
    val state = remember(controller) { ImmersiveModeState(controller) }

    // Sync isImmersive with actual system bar visibility.
    // When BEHAVIOR_DEFAULT is set, an edge-swipe permanently restores the bars.
    // Compose's WindowInsets.systemBars reflects the change, so we reset isImmersive here.
    val density = LocalDensity.current
    val topInset = WindowInsets.systemBars.getTop(density)
    LaunchedEffect(topInset) {
        if (topInset > 0 && state.isImmersive) state.isImmersive = false
    }

    // Always restore system bars when the reader screen leaves the composition.
    DisposableEffect(state) {
        onDispose { state.show() }
    }

    return state
}
