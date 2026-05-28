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
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalDensity
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

@Stable
class ImmersiveModeState(
    private val controller: WindowInsetsControllerCompat,
) {
    var isImmersive by mutableStateOf(false)
        internal set

    // Distinct from isImmersive (TopAppBar visibility): tracks whether controller.hide()
    // has actually been called, so we know whether restoring bars would reflow the WebView.
    private var systemBarsHidden = false

    // Timestamp of the last user-initiated toggle() call. Used to suppress dismissOverlay()
    // for a brief window so a locator event immediately after a tap doesn't re-hide the bar
    // before Compose has a chance to compose the enter animation.
    private var lastToggleMs = 0L

    // Called when the user explicitly changes immersive state via toggle().
    // NOT called for system-initiated changes (onBarsRestoredExternally).
    internal var onUserImmersiveChanged: ((Boolean) -> Unit)? = null

    // Restores both system bars on tap-exit. Clearing systemBarsHidden disables
    // auto-dismiss on page turns; the user must tap to re-enter immersive.
    fun toggle() {
        if (isImmersive) {
            lastToggleMs = SystemClock.elapsedRealtime()
            // systemBarsHidden must be cleared before isImmersive so that any
            // dismissOverlay() call racing on the same frame cannot re-hide the bar.
            systemBarsHidden = false
            isImmersive = false
            controller.show(WindowInsetsCompat.Type.systemBars())
            onUserImmersiveChanged?.invoke(false)
        } else {
            hide()
            onUserImmersiveChanged?.invoke(true)
        }
    }

    // Called on position change: only auto-hides the AppBar when bars are already hidden,
    // so position changes in normal (bars-visible) mode don't dismiss the overlay.
    // Suppressed for TOGGLE_COOLDOWN_MS after the user taps to reveal the bar.
    fun dismissOverlay() {
        val now = SystemClock.elapsedRealtime()
        if (systemBarsHidden && now - lastToggleMs > TOGGLE_COOLDOWN_MS) isImmersive = true
    }

    internal fun hide() {
        // Guard: only call controller.hide() if bars are not already hidden.
        // On some API levels, calling hide() on already-hidden bars triggers a native crash
        // in the WebView/Chromium renderer stack. The toggle() call-path can reach hide()
        // while systemBarsHidden is true (user revealed TopAppBar then tapped again), so
        // we must be idempotent here.
        if (!systemBarsHidden) {
            systemBarsHidden = true
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_DEFAULT
            controller.hide(WindowInsetsCompat.Type.systemBars())
        }
        isImmersive = true
    }

    internal fun show() {
        systemBarsHidden = false
        isImmersive = false
        controller.show(WindowInsetsCompat.Type.systemBars())
    }

    // Called when the system restores bars externally (edge-swipe with BEHAVIOR_DEFAULT).
    // Also fires after toggle() because the topInset watcher in rememberImmersiveModeState
    // sees the 0→positive transition that controller.show(systemBars()) triggers. The call
    // is idempotent here — flags are already in the target state — so it's safe.
    internal fun onBarsRestoredExternally() {
        systemBarsHidden = false
        isImmersive = false
    }

    companion object {
        // After the user taps to reveal the TopAppBar, suppress auto-dismiss for this long
        // so that a locator event arriving in the same Compose frame doesn't immediately
        // undo the reveal before the enter animation can start.
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
    val currentSavedIsImmersive by rememberUpdatedState(savedIsImmersive)
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME && currentSavedIsImmersive) {
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
