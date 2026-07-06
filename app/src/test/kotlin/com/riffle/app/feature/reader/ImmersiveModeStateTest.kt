package com.riffle.app.feature.reader

import android.os.Build
import androidx.core.view.WindowInsetsControllerCompat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ImmersiveModeStateTest {

    private class FakeController : SystemBarsController {
        var hideCount = 0
        var showCount = 0
        var applyImmersiveBehaviorCount = 0
        var reapplyRawCount = 0

        override fun hide() {
            hideCount++
        }

        override fun show() {
            showCount++
        }

        override fun applyImmersiveBehavior() {
            applyImmersiveBehaviorCount++
        }

        override fun reapplyRaw() {
            reapplyRawCount++
        }
    }

    private lateinit var controller: FakeController
    private var nowMs = 0L
    private lateinit var state: ImmersiveModeState

    @Before
    fun setUp() {
        controller = FakeController()
        nowMs = 0L
        state = ImmersiveModeState(controller, clock = { nowMs })
    }

    @Test
    fun `initial state is not immersive`() {
        assertFalse(state.isImmersive)
        assertFalse(state.systemBarsHiddenForTest)
        assertEquals(0, controller.hideCount)
        assertEquals(0, controller.showCount)
    }

    @Test
    fun `hide enters immersive and hides system bars`() {
        state.hide()

        assertTrue(state.isImmersive)
        assertTrue(state.systemBarsHiddenForTest)
        assertEquals(1, controller.hideCount)
        assertEquals(1, controller.applyImmersiveBehaviorCount)
    }

    @Test
    fun `hide is idempotent so duplicate calls do not invoke controller hide again`() {
        // Calling controller.hide() on already-hidden bars crashes the WebView on some API
        // levels (see comment in ImmersiveModeState.hide). Verify the guard prevents that.
        state.hide()
        state.hide()
        state.hide()

        assertEquals(1, controller.hideCount)
        assertTrue(state.isImmersive)
    }

    @Test
    fun `hide force re-invokes controller hide even when flag says hidden`() {
        // This is the sleep-resume bug fix: when the OS restores bars during sleep without
        // our tracker observing it, the flag is stale. force=true must re-call controller.hide()
        // so the nav bar actually gets re-hidden on resume.
        state.hide()
        assertEquals(1, controller.hideCount)

        state.hide(force = true)

        assertEquals(2, controller.hideCount)
        assertTrue(state.isImmersive)
        assertTrue(state.systemBarsHiddenForTest)
    }

    @Test
    fun `toggle from non-immersive enters immersive`() {
        state.toggle()

        assertTrue(state.isImmersive)
        assertEquals(1, controller.hideCount)
    }

    @Test
    fun `toggle from immersive exits immersive and shows bars`() {
        state.hide()
        controller.hideCount = 0 // ignore the prior hide call for clarity

        state.toggle()

        assertFalse(state.isImmersive)
        assertFalse(state.systemBarsHiddenForTest)
        assertEquals(1, controller.showCount)
    }

    @Test
    fun `toggle notifies onUserImmersiveChanged with the new state`() {
        val changes = mutableListOf<Boolean>()
        state.onUserImmersiveChanged = { changes += it }

        state.toggle() // enter
        state.toggle() // exit

        assertEquals(listOf(true, false), changes)
    }

    @Test
    fun `dismissOverlay does nothing when bars are visible`() {
        state.dismissOverlay()

        assertFalse(state.isImmersive)
    }

    @Test
    fun `dismissOverlay hides AppBar when bars are hidden and cooldown elapsed`() {
        state.hide()
        // hide() does not stamp lastToggleMs, so any future time satisfies the cooldown.
        nowMs = ImmersiveModeState.TOGGLE_COOLDOWN_MS + 1

        state.isImmersive = false // simulate user-revealed AppBar without restoring bars
        state.dismissOverlay()

        assertTrue(state.isImmersive)
    }

    @Test
    fun `dismissOverlay is suppressed within TOGGLE_COOLDOWN_MS after toggle`() {
        // Toggle to immersive stamps lastToggleMs via the exit-side branch only. Stamping
        // happens when exiting immersive, so reproduce that path: hide via toggle, then exit
        // via toggle (stamps clock), then re-enter immersive via toggle, then dismissOverlay
        // should still be suppressed within the cooldown of the *exit* tap.
        nowMs = 1_000
        state.toggle() // enter immersive (no stamp)
        nowMs = 2_000
        state.toggle() // exit immersive — stamps lastToggleMs = 2_000
        nowMs = 2_100   // 100 ms after the tap — within cooldown
        state.toggle() // re-enter immersive (hide), no new stamp

        // Simulate a locator event arriving while still in the cooldown window.
        state.isImmersive = false // pretend the AppBar is visible
        state.dismissOverlay()

        // Within cooldown: AppBar should NOT be re-hidden.
        assertFalse(state.isImmersive)
    }

    @Test
    fun `onBarsRestoredExternally re-hides and stays immersive for an unrequested reveal`() {
        // While we believe the bars should be hidden (systemBarsHidden == true), a reveal we did
        // not initiate — a side-edge page-turn swipe flashing the bars under BEHAVIOR_DEFAULT —
        // must be bounced back: re-hide and stay immersive rather than exit.
        state.hide()
        assertTrue(state.systemBarsHiddenForTest)
        controller.hideCount = 0 // ignore the hide() above

        state.onBarsRestoredExternally()

        assertTrue(state.isImmersive)
        assertTrue(state.systemBarsHiddenForTest)
        assertEquals(1, controller.hideCount) // re-hid the flashed bars
        assertEquals(0, controller.showCount)
    }

    @Test
    fun `onBarsRestoredExternally follows a user-requested reveal by exiting immersive`() {
        // After the user reveals the bars via toggle()/show(), systemBarsHidden is already
        // cleared; the watcher's onBarsRestoredExternally then just lets the overlay follow.
        state.show()
        controller.hideCount = 0

        state.onBarsRestoredExternally()

        assertFalse(state.isImmersive)
        assertFalse(state.systemBarsHiddenForTest)
        assertEquals(0, controller.hideCount) // no bounce-back re-hide
    }

    @Test
    fun `show resets state and calls controller show`() {
        state.hide()

        state.show()

        assertFalse(state.isImmersive)
        assertFalse(state.systemBarsHiddenForTest)
        assertEquals(1, controller.showCount)
    }

    @Test
    fun `pre-R uses sticky IMMERSIVE so bars auto-hide after any transient reveal`() {
        // On pre-R BEHAVIOR_DEFAULT maps to non-sticky IMMERSIVE: a focusable Popup opening
        // clears FLAG_HIDE_NAVIGATION|FULLSCREEN and leaves bars visible until we hide() again —
        // but WindowInsetsControllerCompat's own state cache makes that hide() a no-op. Sticky
        // IMMERSIVE (BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE) makes the OS auto-restore fullscreen
        // after any transient reveal, closing the loop without our involvement.
        assertEquals(
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE,
            immersiveSystemBarsBehavior(Build.VERSION_CODES.N_MR1), // API 25 tablet AVD
        )
        assertEquals(
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE,
            immersiveSystemBarsBehavior(Build.VERSION_CODES.Q), // API 29
        )
    }

    @Test
    fun `R plus keeps BEHAVIOR_DEFAULT so side-edge swipes are handled by onBarsRestoredExternally`() {
        // R+ has a modern WindowInsetsController that reliably re-hides after focus regain via
        // the focus-tracker path. Keep BEHAVIOR_DEFAULT so the reader's deliberate
        // "side-edge page-turn swipe stays immersive" logic (onBarsRestoredExternally) still fires.
        assertEquals(
            WindowInsetsControllerCompat.BEHAVIOR_DEFAULT,
            immersiveSystemBarsBehavior(Build.VERSION_CODES.R), // API 30
        )
        assertEquals(
            WindowInsetsControllerCompat.BEHAVIOR_DEFAULT,
            immersiveSystemBarsBehavior(Build.VERSION_CODES.TIRAMISU), // API 33
        )
    }

    @Test
    fun `focus regain while immersive calls reapplyRaw to bypass the compat cache`() {
        // Bug: opening a focusable Compose Popup (e.g. HighlightActionsPopup) or a Dialog steals
        // window focus from the reader; the OS clears the fullscreen flags while the reader Window
        // is unfocused and reveals the status/nav bars behind the popup. On dismiss the reader
        // Window regains focus but the OS still shows the bars — WindowInsetsControllerCompat's
        // own state cache still thinks bars are hidden (it applied the flags earlier), so a
        // vanilla hide() is a no-op. reapplyRaw() writes the flags directly onto the decor view,
        // bypassing the cache, so the OS actually re-hides.
        state.hide()

        state.onWindowFocusChanged(false) // popup opens, reader loses focus
        assertEquals(0, controller.reapplyRawCount) // loss alone is a signal, not an action

        state.onWindowFocusChanged(true) // popup dismisses, reader regains focus
        assertEquals(1, controller.reapplyRawCount) // fix path fired
        assertTrue(state.isImmersive)
    }

    @Test
    fun `focus regain when not immersive does not reapply the flags`() {
        // If the user has intentionally exited immersive (tap-to-toggle), a transient focus loss +
        // regain must NOT re-hide the bars — otherwise a popup dismiss would drag the reader back
        // into immersive against the user's will.
        // state is non-immersive from setUp; simulate the transient focus flip.
        state.onWindowFocusChanged(false)
        state.onWindowFocusChanged(true)

        assertEquals(0, controller.reapplyRawCount)
        assertFalse(state.isImmersive)
    }

    @Test
    fun `focus loss alone does not touch the controller`() {
        // Losing focus is a signal, not an action: we only reapply on regain.
        state.hide()

        state.onWindowFocusChanged(false)

        assertEquals(0, controller.reapplyRawCount)
    }

    @Test
    fun `sleep-resume flow keeps nav bar hidden when immersive before sleep`() {
        // Reproduces the bug scenario:
        //   1. User is immersive (bars hidden via controller.hide()).
        //   2. Phone sleeps; OS restores bars without our tracker observing.
        //   3. On ON_RESUME the composable calls hide(force = true) because savedIsImmersive.
        // Without force = true the guard would skip controller.hide() and the nav bar would
        // stay visible. With force = true, controller.hide() runs again.
        state.hide()
        assertEquals(1, controller.hideCount)

        // ON_RESUME with savedIsImmersive=true must re-issue controller.hide().
        state.hide(force = true)

        assertEquals(2, controller.hideCount)
        assertTrue(state.isImmersive)
    }
}
