package com.riffle.app.feature.reader

import android.os.SystemClock
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.systemBars
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalDensity
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

@Stable
class ImmersiveModeState(
    private val controller: WindowInsetsControllerCompat,
) {
    var isImmersive by mutableStateOf(false)
        internal set

    private var systemBarsHidden = false

    private var lastToggleMs = 0L

    // Called when the user explicitly changes immersive state via toggle().
    // NOT called for system-initiated changes (onBarsRestoredExternally).
    internal var onUserImmersiveChanged: ((Boolean) -> Unit)? = null

    // Does NOT call controller.show() when revealing the AppBar — showing the nav bar
    // changes the WebView's visible height and reflows paginated EPUB content.
    fun toggle() {
        if (isImmersive) {
            lastToggleMs = SystemClock.elapsedRealtime()
            isImmersive = false
            onUserImmersiveChanged?.invoke(false)
        } else {
            hide()
            onUserImmersiveChanged?.invoke(true)
        }
    }

    fun dismissOverlay() {
        val now = SystemClock.elapsedRealtime()
        if (systemBarsHidden && now - lastToggleMs > TOGGLE_COOLDOWN_MS) isImmersive = true
    }

    internal fun hide() {
        systemBarsHidden = true
        isImmersive = true
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_DEFAULT
        controller.hide(WindowInsetsCompat.Type.systemBars())
    }

    internal fun show() {
        systemBarsHidden = false
        isImmersive = false
        controller.show(WindowInsetsCompat.Type.systemBars())
    }

    internal fun onBarsRestoredExternally() {
        systemBarsHidden = false
        isImmersive = false
    }

    companion object {
        const val TOGGLE_COOLDOWN_MS = 500L
    }
}

@Composable
fun rememberImmersiveModeState(): ImmersiveModeState {
    val window = checkNotNull(LocalActivity.current).window
    val controller = remember(window) { WindowInsetsControllerCompat(window, window.decorView) }
    val state = remember(controller) { ImmersiveModeState(controller) }

    // Persists across rotation. Default true = always enter immersive on first open.
    // Only updated by user-initiated toggle()s — NOT by onBarsRestoredExternally().
    var savedIsImmersive by rememberSaveable { mutableStateOf(true) }

    // Wire the user-toggle callback into savedIsImmersive after every recomposition.
    SideEffect {
        state.onUserImmersiveChanged = { savedIsImmersive = it }
    }

    // Enter immersive mode on first open or after rotation if was immersive.
    LaunchedEffect(state) {
        if (savedIsImmersive) state.hide()
    }

    // Re-enter immersive on resume from phone sleep if user hadn't explicitly turned it off.
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val currentSavedIsImmersive by androidx.compose.runtime.rememberUpdatedState(savedIsImmersive)
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME && currentSavedIsImmersive) {
                state.hide()
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }

    // Sync isImmersive with actual system bar visibility so that an edge-swipe
    // (which BEHAVIOR_DEFAULT turns into a permanent bar restore) also shows the TopAppBar overlay.
    val density = LocalDensity.current
    val topInset = WindowInsets.systemBars.getTop(density)
    // prevTopInset distinguishes two cases:
    //   - Bars animating OUT (56→40→20→0): prevTopInset never reaches 0 first,
    //     so we never show the overlay mid-animation.
    //   - Bars restored by edge-swipe (0→20→…): prevTopInset WAS 0,
    //     so the first non-zero value shows the overlay.
    val prevTopInset = remember { mutableStateOf(topInset) }
    LaunchedEffect(topInset) {
        val wasHidden = prevTopInset.value == 0
        prevTopInset.value = topInset
        if (topInset > 0 && wasHidden) state.onBarsRestoredExternally()
    }

    // Always restore system bars when the reader screen leaves the composition.
    DisposableEffect(state) {
        onDispose { state.show() }
    }

    return state
}
