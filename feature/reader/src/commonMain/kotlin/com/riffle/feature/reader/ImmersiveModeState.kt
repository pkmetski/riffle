package com.riffle.feature.reader

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Thin abstraction over the system-bar controls used by [ImmersiveModeState].
 *
 * Lives in commonMain so the state machine can be tested on JVM without instantiating
 * platform-specific window controllers.
 */
interface SystemBarsController {
    fun hide()
    fun show()
    fun applyImmersiveBehavior()

    /**
     * Force-re-apply the raw fullscreen/immersive flags, bypassing the platform controller's state
     * cache. Needed on focus regain after a focusable Popup dismissal on Android; iOS calls
     * [IosStatusBarBridge.setHidden] directly in [hide], so this is a no-op on iOS.
     */
    fun reapplyRaw()
}

/**
 * Shared immersive-mode state machine for the EPUB, PDF, and CBZ readers on both platforms.
 *
 * Tracks whether the reader chrome (TopAppBar, system bars) is hidden, drives the
 * toggle-cooldown window that prevents a locator event from immediately re-hiding the AppBar
 * after a tap-to-reveal, and delegates the actual system-bar manipulation to a
 * platform-supplied [SystemBarsController].
 *
 * State is exposed as a [StateFlow] so Compose and non-Compose callers alike can observe it
 * reactively without introducing a Compose runtime dependency here.
 */
class ImmersiveModeState(
    private val controller: SystemBarsController,
    private val clock: () -> Long,
    initialIsImmersive: Boolean = false,
) {
    private val _isImmersive = MutableStateFlow(initialIsImmersive)

    /** True when the reader chrome is hidden (status/nav bars hidden, TopAppBar not shown). */
    val isImmersive: StateFlow<Boolean> = _isImmersive.asStateFlow()

    private var systemBarsHidden = false
    private var lastToggleMs = 0L

    var onUserImmersiveChanged: ((Boolean) -> Unit)? = null

    private var lastWindowFocused: Boolean? = null

    fun toggle() {
        if (_isImmersive.value) {
            lastToggleMs = clock()
            systemBarsHidden = false
            _isImmersive.value = false
            controller.show()
            onUserImmersiveChanged?.invoke(false)
        } else {
            hide()
            onUserImmersiveChanged?.invoke(true)
        }
    }

    fun dismissOverlay() {
        val now = clock()
        if (now - lastToggleMs <= TOGGLE_COOLDOWN_MS) return
        if (systemBarsHidden) _isImmersive.value = true else hide()
    }

    fun hide(force: Boolean = false) {
        if (force) systemBarsHidden = false
        if (!systemBarsHidden) {
            systemBarsHidden = true
            controller.applyImmersiveBehavior()
            controller.hide()
        }
        _isImmersive.value = true
    }

    fun show() {
        systemBarsHidden = false
        _isImmersive.value = false
        controller.show()
    }

    fun onWindowFocusChanged(focused: Boolean) {
        val wasFocused = lastWindowFocused
        lastWindowFocused = focused
        if (wasFocused == null) return
        if (focused && !wasFocused && _isImmersive.value) controller.reapplyRaw()
    }

    fun onBarsRestoredExternally() {
        if (systemBarsHidden) {
            controller.hide()
            return
        }
        if (_isImmersive.value) {
            systemBarsHidden = true
            controller.hide()
            return
        }
        systemBarsHidden = false
        _isImmersive.value = false
    }

    internal val systemBarsHiddenForTest: Boolean
        get() = systemBarsHidden

    /** For tests: directly set the isImmersive state without going through [toggle]/[hide]/[show]. */
    internal fun setIsImmersiveForTest(value: Boolean) {
        _isImmersive.value = value
    }

    companion object {
        const val TOGGLE_COOLDOWN_MS = 500L
        const val RESUME_SUPPRESSION_MS = 500L
    }
}
