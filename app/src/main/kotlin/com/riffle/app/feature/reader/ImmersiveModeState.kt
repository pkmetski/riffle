package com.riffle.app.feature.reader

import android.os.SystemClock
import androidx.activity.compose.LocalActivity
import androidx.annotation.VisibleForTesting
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
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

/**
 * Thin abstraction over the system-bar controls used by [ImmersiveModeState].
 *
 * Exists so the state machine can be unit-tested without instantiating
 * [WindowInsetsControllerCompat] (which requires an Android `Window`).
 */
internal interface SystemBarsController {
    fun hide()
    fun show()
    fun setBehaviorDefault()
}

private class WindowInsetsBarsController(
    private val delegate: WindowInsetsControllerCompat,
) : SystemBarsController {
    override fun hide() = delegate.hide(WindowInsetsCompat.Type.systemBars())
    override fun show() = delegate.show(WindowInsetsCompat.Type.systemBars())
    override fun setBehaviorDefault() {
        delegate.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_DEFAULT
    }
}

@Stable
class ImmersiveModeState internal constructor(
    private val controller: SystemBarsController,
    private val clock: () -> Long,
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
            lastToggleMs = clock()
            // systemBarsHidden must be cleared before isImmersive so that any
            // dismissOverlay() call racing on the same frame cannot re-hide the bar.
            systemBarsHidden = false
            isImmersive = false
            controller.show()
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
        val now = clock()
        if (systemBarsHidden && now - lastToggleMs > TOGGLE_COOLDOWN_MS) isImmersive = true
    }

    // Guard: only call controller.hide() if bars are not already hidden.
    // On some API levels, calling hide() on already-hidden bars triggers a native crash
    // in the WebView/Chromium renderer stack. The toggle() call-path can reach hide()
    // while systemBarsHidden is true (user revealed TopAppBar then tapped again), so
    // we must be idempotent here.
    //
    // Pass force = true when the tracked flag may be stale relative to the OS (e.g. after
    // returning from sleep, where the system may have restored bars while we still believe
    // them hidden). The flag is reset before the guard so controller.hide() actually runs.
    internal fun hide(force: Boolean = false) {
        if (force) systemBarsHidden = false
        if (!systemBarsHidden) {
            systemBarsHidden = true
            controller.setBehaviorDefault()
            controller.hide()
        }
        isImmersive = true
    }

    internal fun show() {
        systemBarsHidden = false
        isImmersive = false
        controller.show()
    }

    // Called when the topInset watcher in rememberImmersiveModeState sees the bars become
    // visible (0→positive top inset) without us asking. Two sources reach here:
    //
    //  - A user reveal via toggle()/show(): systemBarsHidden was already cleared, so we let the
    //    overlay follow (isImmersive = false) — flags are already in the target state.
    //
    //  - An *unrequested* reveal while we still believe the bars should be hidden
    //    (systemBarsHidden == true): a system edge gesture revealed them. A side-edge page-turn
    //    swipe lands in the OS back-gesture zone, and under BEHAVIOR_DEFAULT that flashes the
    //    system bars — which otherwise drops the reader out of immersive mode on every edge swipe.
    //    We re-hide and stay immersive instead, so the bars are only ever revealed by an explicit
    //    tap-to-toggle. (Verified on an API-33 emulator with gesture navigation: a side-edge swipe
    //    now stays in immersive mode. Note this also suppresses the incidental top-edge
    //    swipe-down reveal — the tap is the supported way to bring the chrome back.)
    internal fun onBarsRestoredExternally() {
        if (systemBarsHidden) {
            // Bars are currently shown (the OS just flashed them) so re-hiding is a real hide,
            // not a double-hide; isImmersive stays true.
            controller.hide()
            return
        }
        systemBarsHidden = false
        isImmersive = false
    }

    @VisibleForTesting
    internal val systemBarsHiddenForTest: Boolean
        get() = systemBarsHidden

    companion object {
        // After the user taps to reveal the TopAppBar, suppress auto-dismiss for this long
        // so that a locator event arriving in the same Compose frame doesn't immediately
        // undo the reveal before the enter animation can start.
        const val TOGGLE_COOLDOWN_MS = 500L

        // After ON_RESUME, ignore a 0→positive topInset transition for this window.
        // While the activity was paused, the system may have restored the bars on its own;
        // that change should not be treated as an edge-swipe restore.
        const val RESUME_SUPPRESSION_MS = 500L
    }
}

@Composable
fun rememberImmersiveModeState(): ImmersiveModeState {
    val window = checkNotNull(LocalActivity.current).window
    val controller = remember(window) {
        WindowInsetsBarsController(WindowInsetsControllerCompat(window, window.decorView))
    }
    val state = remember(controller) {
        ImmersiveModeState(controller, clock = SystemClock::elapsedRealtime)
    }

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
    // Timestamp of the last ON_RESUME. Used to suppress the topInset watcher briefly so a
    // system-initiated bar restore that happened during sleep isn't mistaken for an edge-swipe.
    val lastResumeMs = remember { mutableStateOf(0L) }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                lastResumeMs.value = SystemClock.elapsedRealtime()
                if (currentSavedIsImmersive) {
                    // During sleep the OS may have restored the system bars even though our
                    // tracked flag still says hidden. force = true resyncs the flag so the
                    // idempotency guard doesn't skip controller.hide() and leave the nav bar visible.
                    state.hide(force = true)
                }
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
        if (topInset > 0 && wasHidden) {
            // Suppress within RESUME_SUPPRESSION_MS of ON_RESUME: the 0→positive transition
            // came from the system restoring bars during sleep, not from an edge-swipe.
            // The ON_RESUME observer already called hide() in that case if appropriate.
            val now = SystemClock.elapsedRealtime()
            if (lastResumeMs.value != 0L && now - lastResumeMs.value < ImmersiveModeState.RESUME_SUPPRESSION_MS) {
                return@LaunchedEffect
            }
            state.onBarsRestoredExternally()
        }
    }

    // Re-apply immersive whenever the reader window regains focus after a transient loss. A
    // focusable Popup (e.g. HighlightActionsPopup) or a system Dialog steals focus from the reader
    // Window; while the reader Window is unfocused the OS drops it out of immersive and reveals the
    // status/nav bars behind the popup — layout stays fullscreen so the topInset watcher can't see
    // it (inset stays 0). On focus regain we force-re-hide to restore true immersive; force = true
    // resets the systemBarsHidden guard so controller.hide() actually reaches the OS again.
    val windowInfo = LocalWindowInfo.current
    LaunchedEffect(state, windowInfo) {
        var wasFocused = windowInfo.isWindowFocused
        snapshotFlow { windowInfo.isWindowFocused }.collect { focused ->
            if (focused && !wasFocused && state.isImmersive) state.hide(force = true)
            wasFocused = focused
        }
    }

    // Always restore system bars when the reader screen leaves the composition.
    DisposableEffect(state) {
        onDispose { state.show() }
    }

    return state
}
