package com.riffle.feature.reader.autoscroll

import com.riffle.core.domain.autoscroll.AutoScrollEvent
import com.riffle.core.domain.autoscroll.AutoScrollSpeed
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * What a HUD-pill speed nudge persists.
 *
 * Both readers call this: Android through `FormattingSession.nudgeAutoScroll`, iOS straight from
 * the pill in `IosEpubReaderScreen`. Before it was shared, iOS had no pill at all and Android's
 * copy was the only one — a second implementation on iOS is exactly how the two would have
 * drifted on whether a nudge survives closing the book.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AutoScrollNudgeTest {

    @Test
    fun nudgingAnActiveSessionReturnsTheNewWpmToPersist() = runTest {
        val controller = AutoScrollController.forTest(StandardTestDispatcher(testScheduler))
        controller.dispatch(AutoScrollEvent.Start)
        assertEquals(
            AutoScrollSpeed.DEFAULT_WPM + AutoScrollSpeed.STEP_WPM,
            controller.nudgeSpeedAndPersistableWpm(AutoScrollSpeed.STEP_WPM, AutoScrollSpeed.DEFAULT_WPM),
        )
        controller.release()
    }

    @Test
    fun nudgingWithNoSessionRunningPersistsNothing() = runTest {
        val controller = AutoScrollController.forTest(StandardTestDispatcher(testScheduler))
        assertNull(controller.nudgeSpeedAndPersistableWpm(AutoScrollSpeed.STEP_WPM, AutoScrollSpeed.DEFAULT_WPM))
        controller.release()
    }

    /**
     * At the top of the range the clamp means the nudge is a no-op, and a no-op must not write —
     * otherwise every tap on "faster" at 600 wpm costs a preference round-trip.
     */
    @Test
    fun aClampedNudgeThatChangesNothingPersistsNothing() = runTest {
        val controller = AutoScrollController.forTest(StandardTestDispatcher(testScheduler))
        controller.setDefaultSpeed(AutoScrollSpeed.of(AutoScrollSpeed.MAX_WPM))
        controller.dispatch(AutoScrollEvent.Start)
        assertNull(controller.nudgeSpeedAndPersistableWpm(AutoScrollSpeed.STEP_WPM, AutoScrollSpeed.MAX_WPM))
        controller.release()
    }

    @Test
    fun aNudgeIsPersistedEvenWhileTheSessionIsPaused() = runTest {
        val controller = AutoScrollController.forTest(StandardTestDispatcher(testScheduler))
        controller.dispatch(AutoScrollEvent.Start)
        controller.dispatch(AutoScrollEvent.Pause(com.riffle.core.domain.autoscroll.PauseCause.UserPausedPill))
        assertEquals(
            AutoScrollSpeed.DEFAULT_WPM - AutoScrollSpeed.STEP_WPM,
            controller.nudgeSpeedAndPersistableWpm(-AutoScrollSpeed.STEP_WPM, AutoScrollSpeed.DEFAULT_WPM),
        )
        controller.release()
    }
}
