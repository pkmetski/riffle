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
    fun `onExhausted fires when ticker drains this source, state stays Running for auto-advance`() = runController { c ->
        // Regression: state does NOT flip to Idle on chapter exhaustion — the caller navigates to
        // the next chapter and rebinds, and the [Running] state persists so ticking resumes
        // without a user tap. If this flipped back to Idle, chapter boundaries would stall
        // Cadence and require a manual restart on every page turn.
        var end = 0
        c.bind(FakeSource(listOf("c#s0" to "one two")), onExhausted = { end++ })
        c.dispatch(CadenceEvent.Start)
        runCurrent()
        advanceUntilIdle()
        assertEquals(1, end)
        assertTrue(c.state.value is CadenceState.Running)
    }

    @Test
    fun `rebinding with a Running state auto-plays the new ticker`() = runController { c ->
        // Emulates the reader's chapter auto-advance: exhausted → screen navigates → new
        // chapter's DOM tokenises → VM calls bind() again. Cadence must resume ticking on the
        // new source without a user tap.
        c.bind(FakeSource(listOf("c#s0" to "one two three four five six seven eight")))
        c.dispatch(CadenceEvent.Start)
        runCurrent()
        assertEquals("c#s0", c.currentFragment.value)

        // New chapter arrives.
        c.bind(FakeSource(listOf("d#s0" to "chapter two starts here strongly")))
        runCurrent()
        assertEquals("d#s0", c.currentFragment.value)
        assertTrue(c.state.value is CadenceState.Running)
    }

    @Test
    fun `rebinding while Paused stays paused and does not auto-play`() = runController { c ->
        // Regression: user-initiated pause should survive a chapter rebind. If auto-play ignored
        // the paused state we'd override the user's stop-tap.
        c.bind(FakeSource(listOf("c#s0" to "one two three four five six seven eight")))
        c.dispatch(CadenceEvent.Start)
        runCurrent()
        c.dispatch(CadenceEvent.Pause(PauseCause.PanelOpen))
        assertTrue(c.state.value is CadenceState.Paused)

        c.bind(FakeSource(listOf("d#s0" to "next chapter one two three four")))
        runCurrent()
        assertTrue(c.state.value is CadenceState.Paused)
        assertNull(c.currentFragment.value)
    }

    @Test
    fun `rebinding a superset source keeps the current fragment position`() = runController { c ->
        // Regression (user report 2026-07-07): "switching between reading modes restarts
        // Cadence from the wrong location". Continuous mode fires `bind()` on every chapter
        // that enters the sliding window; a paginated formatting reflow / rotation re-tokenises
        // the current chapter and rebinds too. The old bind() cleared `_currentFragment` before
        // handing to the fresh ticker, so if Cadence was running, play() fell back to
        // orderedFragments[0] — cd-0 of the first chapter tokenised this session (usually
        // "Cover design: Wiley" or similar). Fix: bind captures the previous fragment and
        // restores it on the fresh ticker via goTo when the ref is still in the merged order.
        c.bind(FakeSource(listOf(
            "c#s0" to "first sentence here",
            "c#s1" to "second sentence for good measure",
        )))
        c.dispatch(CadenceEvent.Start)
        runCurrent()
        advanceTimeBy(3_500L) // ~1 word/100ms at 600wpm; past s0 into s1 (3 words dwell = ~300ms)
        val positionBeforeRebind = c.currentFragment.value
        assertEquals("c#s1", positionBeforeRebind)

        // Rebind with the SAME two fragments plus an extra (mode switch / sliding-window add).
        c.bind(FakeSource(listOf(
            "c#s0" to "first sentence here",
            "c#s1" to "second sentence for good measure",
            "c#s2" to "third sentence extra",
        )))
        runCurrent()
        assertEquals("Cadence must resume where it left off, not reset to cd-0", "c#s1", c.currentFragment.value)
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

    // setPaused(true, cause) → setPaused(false, cause) resumes; setPaused(false, otherCause)
    // does NOT. Backs the pause-while-selecting behaviour so a `TextSelection` resume can't
    // silently un-park a longer-lived cause (e.g. `PanelOpen`) that started mid-selection.
    @Test
    fun `setPaused with matching cause resumes`() = runController { c ->
        c.bind(FakeSource(listOf("c#s0" to "one two three four five six seven eight")))
        c.dispatch(CadenceEvent.Start)
        runCurrent()
        c.setPaused(paused = true, cause = PauseCause.TextSelection)
        assertTrue(c.state.value is CadenceState.Paused)
        assertEquals(PauseCause.TextSelection, (c.state.value as CadenceState.Paused).cause)

        c.setPaused(paused = false, cause = PauseCause.TextSelection)
        assertTrue(c.state.value is CadenceState.Running)
    }

    @Test
    fun `setPaused with non-matching resume cause does not resume`() = runController { c ->
        c.bind(FakeSource(listOf("c#s0" to "one two three four five six seven eight")))
        c.dispatch(CadenceEvent.Start)
        runCurrent()
        c.setPaused(paused = true, cause = PauseCause.PanelOpen)

        // Selection ended → attempts a scoped resume with TextSelection. The reducer's current
        // cause is PanelOpen, so this MUST be ignored — otherwise closing a selection would
        // un-park a panel-open pause that started while the selection was still active.
        c.setPaused(paused = false, cause = PauseCause.TextSelection)
        assertTrue(c.state.value is CadenceState.Paused)
        assertEquals(PauseCause.PanelOpen, (c.state.value as CadenceState.Paused).cause)
    }

    @Test
    fun `setPaused pausing while already paused updates the cause`() = runController { c ->
        c.bind(FakeSource(listOf("c#s0" to "one two three four five six seven eight")))
        c.dispatch(CadenceEvent.Start)
        runCurrent()
        c.setPaused(paused = true, cause = PauseCause.TextSelection)
        // A panel opens during the selection: pause carrier flips to PanelOpen.
        c.setPaused(paused = true, cause = PauseCause.PanelOpen)
        assertEquals(PauseCause.PanelOpen, (c.state.value as CadenceState.Paused).cause)
        // The selection then ends: a scoped TextSelection resume must NOT un-park.
        c.setPaused(paused = false, cause = PauseCause.TextSelection)
        assertTrue(c.state.value is CadenceState.Paused)
    }
}
