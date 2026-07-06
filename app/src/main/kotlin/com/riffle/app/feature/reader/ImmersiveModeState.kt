package com.riffle.app.feature.reader

import android.os.Build
import android.os.SystemClock
import android.view.View
import android.view.Window
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
    fun applyImmersiveBehavior()

    /**
     * Force-re-apply the raw fullscreen/immersive flags directly on the underlying `Window`,
     * bypassing [WindowInsetsControllerCompat]'s state cache. Needed on focus regain after a
     * focusable Popup dismissal: the compat layer's own bookkeeping still thinks bars are hidden
     * (because it applied the flags earlier), so `hide()` is a no-op — but the OS has actually
     * cleared `FLAG_HIDE_NAVIGATION|FULLSCREEN` during the focus loss and won't restore them until
     * we write the flags again. On pre-R this reasserts the raw SYSTEM_UI_FLAG_*; on R+ we just
     * fall back to `hide()` since the modern controller has no equivalent cache bug.
     */
    fun reapplyRaw()
}

@Suppress("DEPRECATION")
private class WindowInsetsBarsController(
    private val window: Window,
    private val delegate: WindowInsetsControllerCompat,
    private val sdkInt: Int = Build.VERSION.SDK_INT,
) : SystemBarsController {
    override fun hide() = delegate.hide(WindowInsetsCompat.Type.systemBars())
    override fun show() = delegate.show(WindowInsetsCompat.Type.systemBars())
    override fun applyImmersiveBehavior() {
        delegate.systemBarsBehavior = immersiveSystemBarsBehavior(sdkInt)
    }

    override fun reapplyRaw() {
        if (sdkInt >= Build.VERSION_CODES.R) {
            delegate.hide(WindowInsetsCompat.Type.systemBars())
            return
        }
        // On pre-R, once the OS has entered the "transparent overlay" state (bars drawn on top of
        // still-fullscreen layout after focus loss to a focusable Popup), the immersive flags on
        // decor are still all set but the OS ignores them until it sees a real transition. Force
        // one: first clear the fullscreen/hide flags (visible-bars state), then on the next frame
        // re-set them with IMMERSIVE_STICKY. The two-step toggle reads to the OS as a genuine
        // reveal→hide cycle so it drops the transparent overlay and returns to true fullscreen.
        val decor = window.decorView
        val immersiveFlags = View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_FULLSCREEN or
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
            View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        val layoutOnly = View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        decor.systemUiVisibility = layoutOnly
        decor.post { decor.systemUiVisibility = immersiveFlags }
    }
}

/**
 * On pre-R (API < 30) the AndroidX compat layer maps [BEHAVIOR_DEFAULT] to the non-sticky
 * `SYSTEM_UI_FLAG_IMMERSIVE` flag: when a focusable Popup opens or the user taps outside, the
 * OS clears `FLAG_HIDE_NAVIGATION|FULLSCREEN` but leaves `LAYOUT_HIDE_NAVIGATION|LAYOUT_FULLSCREEN`
 * set, so the status/nav bars come back as transparent overlays and stay. Because the layout
 * flags remain, `WindowInsets.systemBars.getTop()` stays 0 and the topInset watcher can't
 * notice — and calling [WindowInsetsControllerCompat.hide] after the fact is a no-op because the
 * compat layer thinks the flags are already applied.
 *
 * [BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE] sets `IMMERSIVE_STICKY` on pre-R, so the system
 * auto-hides the bars again after any transient reveal (popup dismiss, tap-outside, focus regain).
 * On R+ we keep [BEHAVIOR_DEFAULT] because the reader deliberately treats a side-edge page-turn
 * swipe as a permanent reveal (see [ImmersiveModeState.onBarsRestoredExternally]) — that
 * documented behavior only works with the non-transient default.
 */
@VisibleForTesting
internal fun immersiveSystemBarsBehavior(sdkInt: Int): Int =
    if (sdkInt < Build.VERSION_CODES.R) {
        WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    } else {
        WindowInsetsControllerCompat.BEHAVIOR_DEFAULT
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
            controller.applyImmersiveBehavior()
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
    // Reader-window focus tracker. When the reader Window loses focus to a transient window
    // (focusable Compose Popup, floating Dialog, ActionMode toolbar), the OS drops the reader
    // out of immersive: the SYSTEM_UI_FLAG_IMMERSIVE flag it applied only takes effect while the
    // window is focused, so on focus loss the status/nav bars are drawn back over the still
    // layout-fullscreen reader. On focus regain the flags remain in the cleared state and no path
    // re-applies the hide — the topInset watcher can't see the drift because the inset stays 0.
    //
    // Call [onWindowFocusChanged] from the composable's LocalWindowInfo.isWindowFocused observer.
    // On the false→true transition we force-re-hide when [isImmersive], so controller.hide()
    // reaches the OS again and clears the visible bars.
    private var lastWindowFocused = true
    internal fun onWindowFocusChanged(focused: Boolean) {
        val wasFocused = lastWindowFocused
        lastWindowFocused = focused
        if (focused && !wasFocused && isImmersive) controller.reapplyRaw()
    }

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
        WindowInsetsBarsController(window, WindowInsetsControllerCompat(window, window.decorView))
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
        snapshotFlow { windowInfo.isWindowFocused }
            .collect { focused -> state.onWindowFocusChanged(focused) }
    }

    // Always restore system bars when the reader screen leaves the composition.
    DisposableEffect(state) {
        onDispose { state.show() }
    }

    return state
}
