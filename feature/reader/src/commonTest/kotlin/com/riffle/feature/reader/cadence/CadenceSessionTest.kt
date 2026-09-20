package com.riffle.feature.reader.cadence

import com.riffle.core.domain.SentenceQuote
import com.riffle.core.domain.autoscroll.AutoScrollSpeed
import com.riffle.core.domain.cadence.CadenceState
import com.riffle.core.domain.cadence.PauseCause
import com.riffle.core.domain.cadence.speedOrNull
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The host-independent half of a Cadence session, exercised on the JVM **and** on
 * `iosSimulatorArm64` — this is the code path both readers run, so these assertions are the iOS
 * coverage for the reader wiring as much as the Android one.
 *
 * Two of them pin defects that shipped on Android and would have been copied onto iOS verbatim:
 * the stored `cadenceWpm` never reaching a running session, and a speed nudge never being
 * persisted.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CadenceSessionTest {

    private fun quotes(vararg refs: Pair<String, String>): Map<String, SentenceQuote> =
        refs.associate { it.first to SentenceQuote(before = "", highlight = it.second, after = "") }

    private fun hrefs(vararg refs: String): Map<String, String> =
        refs.associateWith { it.substringBefore('#') }

    private fun runSession(
        persistWpm: (Int) -> Unit = {},
        block: suspend TestScope.(CadenceSession) -> Unit,
    ) = runTest {
        val controller = CadenceController(StandardTestDispatcher(testScheduler))
        // backgroundScope so the session's own collectors are cancelled with the test.
        val session = CadenceSession(
            controller = controller,
            scope = backgroundScope,
            persistWpm = persistWpm,
        )
        try {
            block(session)
        } finally {
            controller.release()
        }
    }

    // ── Defect 1: the stored speed never reached the ticker ────────────────────────────────────

    /**
     * `CadenceController.setDefaultSpeed` had no production caller on either platform, so a
     * reader who set 400 wpm in Settings still got [AutoScrollSpeed.Default]. Reverting
     * [CadenceSession.bindDefaultSpeed]'s `setDefaultSpeed` call makes the asserted speed fall
     * back to the default and this test go red.
     */
    @Test
    fun boundPreferenceWpmIsTheSpeedAStartRunsAt() = runSession { session ->
        val wpm = MutableStateFlow(400)
        session.bindDefaultSpeed(wpm)
        runCurrent()

        session.startWithoutProbe()
        runCurrent()

        assertEquals(
            AutoScrollSpeed.of(400),
            session.state.value.speedOrNull,
            "Start must run at the stored cadenceWpm, not AutoScrollSpeed.Default",
        )
    }

    @Test
    fun changingThePreferenceWhileIdleChangesTheNextStart() = runSession { session ->
        val wpm = MutableStateFlow(400)
        session.bindDefaultSpeed(wpm)
        runCurrent()
        wpm.value = 150
        runCurrent()

        session.startWithoutProbe()
        runCurrent()

        assertEquals(AutoScrollSpeed.of(150), session.state.value.speedOrNull)
    }

    // ── Defect 2: a nudge was never written back ───────────────────────────────────────────────

    /**
     * The HUD pill's ± and the volume keys moved the live ticker and wrote nothing, so the speed
     * snapped back to the stored value on the next open while Auto-Scroll's identical gesture
     * survived. Reverting the `?.let(persistWpm)` in [CadenceSession.nudge] leaves [persisted]
     * empty and this test red.
     */
    @Test
    fun nudgingTheSpeedPersistsTheNewWpm() {
        val persisted = mutableListOf<Int>()
        runSession(persistWpm = { persisted += it }) { session ->
            session.bindDefaultSpeed(MutableStateFlow(300))
            runCurrent()
            session.startWithoutProbe()
            runCurrent()

            session.nudge(AutoScrollSpeed.STEP_WPM, storedWpm = 300)

            assertEquals(
                listOf(300 + AutoScrollSpeed.STEP_WPM),
                persisted,
                "a nudge must reach the preference store, not only the live ticker",
            )
        }
    }

    @Test
    fun nudgingWithNoRunningSessionPersistsNothing() {
        val persisted = mutableListOf<Int>()
        runSession(persistWpm = { persisted += it }) { session ->
            session.nudge(AutoScrollSpeed.STEP_WPM, storedWpm = 300)
            assertTrue(persisted.isEmpty(), "an idle session has no speed to nudge")
        }
    }

    @Test
    fun aNudgeThatChangesNothingPersistsNothing() {
        val persisted = mutableListOf<Int>()
        runSession(persistWpm = { persisted += it }) { session ->
            session.bindDefaultSpeed(MutableStateFlow(AutoScrollSpeed.MAX_WPM))
            runCurrent()
            session.startWithoutProbe()
            runCurrent()
            // Already clamped at the top of the range — the nudge is a no-op.
            session.nudge(AutoScrollSpeed.STEP_WPM, storedWpm = AutoScrollSpeed.MAX_WPM)
            assertTrue(persisted.isEmpty())
        }
    }

    // ── The tokenise → bind → start pipeline ───────────────────────────────────────────────────

    @Test
    fun sentencesAccumulateAcrossChaptersInReadingOrder() = runSession { session ->
        session.onChapterTokenised(quotes("c1#cd-0" to "first"), hrefs("c1#cd-0"))
        runCurrent()
        session.onChapterTokenised(quotes("c2#cd-0" to "second"), hrefs("c2#cd-0"))
        runCurrent()

        assertEquals(listOf("c1#cd-0", "c2#cd-0"), session.quotes.value.keys.toList())
        assertEquals(mapOf("c1#cd-0" to "c1", "c2#cd-0" to "c2"), session.chapterHrefs)
    }

    /**
     * The probe reports the sentence the reader is looking at; the ticker must start there.
     * Without the `goTo`, `play()` falls to `orderedFragments[0]` — the first sentence of
     * whichever chapter was tokenised first — and Readium scrolls the reader back to it.
     */
    @Test
    fun startingFromTheProbedSentenceBeginsThere() = runSession { session ->
        session.onChapterTokenised(
            quotes("c1#cd-0" to "front matter", "c1#cd-1" to "page one", "c1#cd-2" to "page two"),
            hrefs("c1#cd-0", "c1#cd-1", "c1#cd-2"),
        )
        runCurrent()

        session.onPageTopResolved("c1", "c1#cd-2")
        runCurrent()

        assertEquals("c1#cd-2", session.currentFragment.value)
    }

    @Test
    fun aFailedProbeStartsAtTheCurrentChaptersFirstSentence() = runSession { session ->
        session.onChapterTokenised(quotes("c1#cd-0" to "first chapter"), hrefs("c1#cd-0"))
        runCurrent()
        session.onChapterTokenised(quotes("c2#cd-0" to "second chapter"), hrefs("c2#cd-0"))
        runCurrent()

        session.onPageTopResolved("c2", null)
        runCurrent()

        assertEquals(
            "c2#cd-0",
            session.currentFragment.value,
            "a failed probe must fall back within the current chapter, not to the first one tokenised",
        )
    }

    @Test
    fun drainingAChapterEmitsTheEndOfChapterEvent() = runSession { session ->
        var fired = 0
        backgroundScope.launch { session.endOfChapterEvents.collect { fired++ } }
        runCurrent()
        session.onChapterTokenised(quotes("c1#cd-0" to "one"), hrefs("c1#cd-0"))
        runCurrent()
        session.onPageTopResolved("c1", "c1#cd-0")
        runCurrent()
        // One short sentence at the default pace drains quickly; advance well past its dwell.
        testScheduler.advanceTimeBy(60_000L)
        runCurrent()
        assertTrue(fired > 0, "the reader relies on this event to page to the next chapter")
    }

    // ── Lifecycle + pause plumbing ─────────────────────────────────────────────────────────────

    @Test
    fun pauseCarriesItsCauseAndAScopedResumeOnlyLiftsThatCause() = runSession { session ->
        session.bindDefaultSpeed(MutableStateFlow(300))
        runCurrent()
        session.startWithoutProbe()
        runCurrent()

        session.pauseFor(PauseCause.AutoScrollStarted)
        assertEquals(PauseCause.AutoScrollStarted, (session.state.value as CadenceState.Paused).cause)

        session.setPaused(paused = false, cause = PauseCause.TextSelection)
        assertTrue(
            session.state.value is CadenceState.Paused,
            "a text-selection resume must not un-park a pause auto-scroll owns",
        )

        session.setPaused(paused = false, cause = PauseCause.AutoScrollStarted)
        assertTrue(session.state.value is CadenceState.Running)
    }

    @Test
    fun resetDropsTheAccumulatedSentencesAndStopsTheTicker() = runSession { session ->
        session.onChapterTokenised(quotes("c1#cd-0" to "one"), hrefs("c1#cd-0"))
        runCurrent()
        session.startWithoutProbe()
        runCurrent()

        session.reset()

        assertEquals(emptyMap(), session.quotes.value)
        assertEquals(emptyMap(), session.chapterHrefs)
        assertEquals(CadenceState.Idle, session.state.value)
        assertNull(session.currentFragment.value)
    }
}
