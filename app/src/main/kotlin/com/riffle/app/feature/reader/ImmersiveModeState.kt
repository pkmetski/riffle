package com.riffle.app.feature.reader

import android.os.SystemClock
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

    // Distinct from isImmersive (TopAppBar visibility): tracks whether controller.hide()
    // has actually been called, so we know whether restoring bars would reflow the WebView.
    private var systemBarsHidden = false

    // Timestamp of the last user-initiated toggle() call. Used to suppress dismissOverlay()
    // for a brief window so a locator event immediately after a tap doesn't re-hide the bar
    // before Compose has a chance to compose the enter animation.
    private var lastToggleMs = 0L

    // Does NOT call controller.show() when revealing the AppBar — showing the nav bar
    // changes the WebView's visible height and reflows paginated EPUB content.
    fun toggle() {
        if (isImmersive) {
            lastToggleMs = SystemClock.elapsedRealtime()
            isImmersive = false
        } else {
            hide()
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

    // Called when the system restores bars externally (edge-swipe with BEHAVIOR_DEFAULT).
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

    // Always enter immersive mode when the reader opens.
    LaunchedEffect(state) {
        state.hide()
    }

    // Sync isImmersive with actual system bar visibility so that an edge-swipe
    // (which BEHAVIOR_DEFAULT turns into a permanent bar restore) also shows the
    // TopAppBar overlay.
    //
    // prevTopInset distinguishes two cases:
    //   - Bars animating OUT (56→40→20→0): prevTopInset never reaches 0 first,
    //     so we never show the overlay mid-animation.
    //   - Bars restored by edge-swipe (0→20→…): prevTopInset WAS 0,
    //     so the first non-zero value shows the overlay.
    val density = LocalDensity.current
    val topInset = WindowInsets.systemBars.getTop(density)
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
