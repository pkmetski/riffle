package com.riffle.app.feature.reader.readaloud

import com.riffle.core.domain.MediaOverlayClip
import com.riffle.core.domain.ReadaloudTrack
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Regression coverage for the audio-clock→fragment lookup extracted from [PlayerCoordinator] into
 * [AudioClockTicker] (ADR 0039 Task 5). Pins the same behaviour [PlayerCoordinator] relied on: a
 * controller state update resolves via [ReadaloudTrack.activeClipAt] to a fragment + progress, and a
 * miss (no track, or a position that hits no clip) clears both back to null.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AudioClockTickerTest {

    private val clip1 = MediaOverlayClip(
        textFragmentRef = "chapter1.xhtml#s0",
        audioSrc = "audio1.mp3",
        clipBeginSec = 0.0,
        clipEndSec = 4.0,
    )
    private val clip2 = MediaOverlayClip(
        textFragmentRef = "chapter1.xhtml#s1",
        audioSrc = "audio1.mp3",
        clipBeginSec = 4.0,
        clipEndSec = 10.0,
    )
    private val track = ReadaloudTrack(listOf(clip1, clip2))

    private class FakeReadaloudController : ReadaloudController() {
        private val _state = MutableStateFlow(PlaybackState())
        override val state: StateFlow<PlaybackState> = _state

        val playFromFragmentCalls = mutableListOf<String>()
        var playCalled = 0
        var pauseCalled = 0
        var stopCalled = 0

        fun emit(state: PlaybackState) {
            _state.value = state
        }

        override fun play() { playCalled++ }
        override fun pause() { pauseCalled++ }
        override fun stop() { stopCalled++ }
        override fun playFromFragment(fragmentRef: String) { playFromFragmentCalls.add(fragmentRef) }
    }

    @Test
    fun `resolves currentFragment and progress from a matching clip`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val controller = FakeReadaloudController()
        val ticker = AudioClockTicker(controller, { track }, TestScope(dispatcher))

        // 2 seconds into clip1's [0, 4) range -> 50% progress through the sentence.
        controller.emit(ReadaloudController.PlaybackState(currentAudioSrc = "audio1.mp3", positionSec = 2.0))
        dispatcher.scheduler.runCurrent()

        assertEquals("chapter1.xhtml#s0", ticker.currentFragment.value)
        assertEquals(clip1.progressAt(2.0), ticker.progress.value)
    }

    @Test
    fun `no matching clip clears currentFragment and progress`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val controller = FakeReadaloudController()
        val ticker = AudioClockTicker(controller, { track }, TestScope(dispatcher))

        controller.emit(ReadaloudController.PlaybackState(currentAudioSrc = "audio1.mp3", positionSec = 2.0))
        dispatcher.scheduler.runCurrent()
        assertEquals("chapter1.xhtml#s0", ticker.currentFragment.value)

        // Position falls in the gap after the last clip -> no active clip.
        controller.emit(ReadaloudController.PlaybackState(currentAudioSrc = "audio1.mp3", positionSec = 99.0))
        dispatcher.scheduler.runCurrent()

        assertNull(ticker.currentFragment.value)
        assertNull(ticker.progress.value)
    }

    @Test
    fun `null track never resolves a fragment`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val controller = FakeReadaloudController()
        val ticker = AudioClockTicker(controller, { null }, TestScope(dispatcher))

        controller.emit(ReadaloudController.PlaybackState(currentAudioSrc = "audio1.mp3", positionSec = 2.0))
        dispatcher.scheduler.runCurrent()

        assertNull(ticker.currentFragment.value)
        assertNull(ticker.progress.value)
    }

    @Test
    fun `goTo delegates to controller playFromFragment`() {
        val controller = FakeReadaloudController()
        val ticker = AudioClockTicker(controller, { track }, TestScope(StandardTestDispatcher()))

        ticker.goTo("chapter1.xhtml#s1")

        assertEquals(listOf("chapter1.xhtml#s1"), controller.playFromFragmentCalls)
    }

    @Test
    fun `play pause stop delegate to controller`() {
        val controller = FakeReadaloudController()
        val ticker = AudioClockTicker(controller, { track }, TestScope(StandardTestDispatcher()))

        ticker.play()
        ticker.pause()
        ticker.stop()

        assertEquals(1, controller.playCalled)
        assertEquals(1, controller.pauseCalled)
        assertEquals(1, controller.stopCalled)
    }

    @Test
    fun `reset synchronously clears fragment and progress`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val controller = FakeReadaloudController()
        val ticker = AudioClockTicker(controller, { track }, TestScope(dispatcher))

        controller.emit(ReadaloudController.PlaybackState(currentAudioSrc = "audio1.mp3", positionSec = 2.0))
        dispatcher.scheduler.runCurrent()
        assertEquals("chapter1.xhtml#s0", ticker.currentFragment.value)

        ticker.reset()

        assertNull(ticker.currentFragment.value)
        assertNull(ticker.progress.value)
    }
}
