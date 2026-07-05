package com.riffle.app.feature.reader.cadence

import com.riffle.core.domain.SentenceQuote
import com.riffle.core.domain.autoscroll.AutoScrollSpeed
import com.riffle.core.domain.cadence.CadenceEvent
import com.riffle.core.domain.cadence.CadenceState
import com.riffle.core.domain.cadence.PauseCause
import com.riffle.core.domain.sentence.FragmentRef
import com.riffle.core.domain.sentence.SentenceSource
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CadenceControllerTest {

    private class FakeSource(private val order: List<Pair<FragmentRef, String>>) : SentenceSource {
        override suspend fun loadAll(): Map<FragmentRef, SentenceQuote> =
            order.associate { it.first to SentenceQuote(before = "", highlight = it.second, after = "") }
        override suspend fun chapterHrefs(): Map<FragmentRef, String> =
            order.associate { it.first to it.first.substringBefore('#') }
    }

    private fun runController(
        block: suspend kotlinx.coroutines.test.TestScope.(CadenceController) -> Unit,
    ) = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val controller = CadenceController(dispatcher)
        controller.setDefaultSpeed(AutoScrollSpeed.of(600))
        try {
            block(controller)
        } finally {
            controller.release()
        }
    }

    @Test
    fun `dispatch Start with no bound source is a state-only no-op`() = runController { c ->
        c.dispatch(CadenceEvent.Start)
        runCurrent()
        // Reducer flips to Running even with no bound source — the caller decides whether Start
        // is legal (top-bar toggle only appears when a source is available). We just check the
        // ticker didn't crash and no fragment was emitted.
        assertEquals(CadenceState.Running(AutoScrollSpeed.of(600)), c.state.value)
        assertNull(c.currentFragment.value)
    }

    @Test
    fun `bind then Start begins ticking through fragments`() = runController { c ->
        c.bind(FakeSource(listOf(
            "c#s0" to "one two three four five six seven eight",
            "c#s1" to "next sentence",
        )))
        c.dispatch(CadenceEvent.Start)
        runCurrent()
        assertEquals("c#s0", c.currentFragment.value)
        assertTrue(c.state.value is CadenceState.Running)

        // After s0 dwell (8 words * 100ms/word = 800ms at 600 wpm), move to s1.
        advanceTimeBy(900L)
        assertEquals("c#s1", c.currentFragment.value)
    }

    @Test
    fun `Pause freezes current fragment then Resume continues`() = runController { c ->
        c.bind(FakeSource(listOf(
            "c#s0" to "one two three four five six seven eight",
            "c#s1" to "next sentence here",
        )))
        c.dispatch(CadenceEvent.Start)
        runCurrent()
        advanceTimeBy(200L)
        c.dispatch(CadenceEvent.Pause(PauseCause.PanelOpen))
        val frozen = c.currentFragment.value
        advanceTimeBy(10_000L)
        assertEquals(frozen, c.currentFragment.value)
        assertTrue(c.state.value is CadenceState.Paused)

        c.dispatch(CadenceEvent.Resume)
        runCurrent()
        assertTrue(c.state.value is CadenceState.Running)
    }

    @Test
    fun `NudgeSpeed updates state speed and forwards to ticker`() = runController { c ->
        c.bind(FakeSource(listOf("c#s0" to "one two")))
        c.dispatch(CadenceEvent.Start)
        runCurrent()
        c.dispatch(CadenceEvent.NudgeSpeed(-100))
        assertEquals(AutoScrollSpeed.of(500), (c.state.value as CadenceState.Running).speed)
    }

    @Test
    fun `pauseFor(ReadaloudStarted) freezes cadence`() = runController { c ->
        c.bind(FakeSource(listOf("c#s0" to "one two three four five six seven eight")))
        c.dispatch(CadenceEvent.Start)
        runCurrent()
        c.pauseFor(PauseCause.ReadaloudStarted)
        val paused = c.state.value
        assertTrue(paused is CadenceState.Paused && paused.cause == PauseCause.ReadaloudStarted)
    }

    @Test
    fun `onEndOfBook fires when ticker exhausts and state becomes Idle`() = runController { c ->
        var end = 0
        c.bind(FakeSource(listOf("c#s0" to "one two")), onEndOfBook = { end++ })
        c.dispatch(CadenceEvent.Start)
        runCurrent()
        advanceUntilIdle()
        assertEquals(1, end)
        assertEquals(CadenceState.Idle, c.state.value)
    }

    @Test
    fun `unbind clears fragment and stops ticker`() = runController { c ->
        c.bind(FakeSource(listOf("c#s0" to "one two three")))
        c.dispatch(CadenceEvent.Start)
        runCurrent()
        c.unbind()
        assertNull(c.currentFragment.value)
        assertEquals(CadenceState.Idle, c.state.value)
    }
}
