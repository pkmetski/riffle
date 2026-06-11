package com.riffle.app.feature.reader

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Verifies the wiring that drops the reader into immersive mode when readaloud starts.
 * Drives a real [ImmersiveModeState] (backed by a fake [SystemBarsController]) through the
 * composable, asserting both the immersive flag and the actual hide()/show() calls.
 */
@RunWith(AndroidJUnit4::class)
class ImmersiveOnReadaloudPlayTest {

    @get:Rule
    val rule = createComposeRule()

    private class FakeController : SystemBarsController {
        var hideCount = 0
        var showCount = 0
        override fun hide() { hideCount++ }
        override fun show() { showCount++ }
        override fun setBehaviorDefault() {}
    }

    private fun setContent(
        controller: FakeController,
        initialPlaying: Boolean,
    ): Pair<ImmersiveModeState, (Boolean) -> Unit> {
        val state = ImmersiveModeState(controller, clock = { 0L })
        // Hoisted above setContent so it survives recomposition (a bare mutableStateOf inside
        // the composable would reset to initialPlaying on every recompose).
        val playing = mutableStateOf(initialPlaying)
        rule.setContent {
            ImmersiveOnReadaloudPlay(isReadaloudPlaying = playing.value, immersiveState = state)
        }
        return state to { value: Boolean -> playing.value = value }
    }

    @Test
    fun startingPlayback_entersImmersive() {
        val controller = FakeController()
        val (state, setPlaying) = setContent(controller, initialPlaying = false)
        rule.waitForIdle()
        assertFalse(state.isImmersive)
        assertEquals(0, controller.hideCount)

        setPlaying(true)
        rule.waitForIdle()

        assertTrue(state.isImmersive)
        assertEquals(1, controller.hideCount)
    }

    @Test
    fun startingPlaybackInitially_entersImmersive() {
        val controller = FakeController()
        val (state, _) = setContent(controller, initialPlaying = true)
        rule.waitForIdle()

        assertTrue(state.isImmersive)
        assertEquals(1, controller.hideCount)
    }

    @Test
    fun pausing_doesNotRestoreChrome() {
        val controller = FakeController()
        val (state, setPlaying) = setContent(controller, initialPlaying = true)
        rule.waitForIdle()
        assertTrue(state.isImmersive)

        setPlaying(false) // pause
        rule.waitForIdle()

        // One-way: pausing leaves immersive untouched and never calls show().
        assertTrue(state.isImmersive)
        assertEquals(0, controller.showCount)
    }

    @Test
    fun resumingAfterManualReveal_reHidesChrome() {
        val controller = FakeController()
        val (state, setPlaying) = setContent(controller, initialPlaying = true)
        rule.waitForIdle()
        assertEquals(1, controller.hideCount)

        setPlaying(false)       // pause
        rule.waitForIdle()
        state.toggle()          // user taps to reveal the bars while paused
        rule.waitForIdle()
        assertFalse(state.isImmersive)

        setPlaying(true)        // resume
        rule.waitForIdle()

        // Every transition into playing re-hides, even after a manual reveal.
        assertTrue(state.isImmersive)
    }
}
