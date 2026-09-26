package com.riffle.shared.sync

import com.riffle.core.domain.DefaultApplicationScope
import com.riffle.core.sync.CycleOutcome
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * [IosAnnotationSweepEnqueuer] must actually run the sweep (#1101 — the previous binding was
 * `AnnotationSweepEnqueuer { }`), and must collapse duplicate requests while one run is in flight
 * the way Android's `ExistingWorkPolicy.KEEP` does.
 *
 * The enqueuer launches on the survivable scope, which these tests back with `backgroundScope`;
 * `advanceUntilIdle` only drains foreground work, so the scheduler is pumped with `runCurrent`.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class IosAnnotationSweepEnqueuerTest {

    @Test
    fun enqueueRunsTheSweep() = runTest {
        val scope = DefaultApplicationScope(backgroundScope)
        var runs = 0
        val enqueuer = IosAnnotationSweepEnqueuer(scope) {
            runs++
            CycleOutcome.Success(1L)
        }

        enqueuer.enqueue()
        testScheduler.runCurrent()

        assertEquals(1, runs)
    }

    @Test
    fun enqueueWhileASweepIsInFlightCoalescesAndRunsAgainAfterwards() = runTest(StandardTestDispatcher()) {
        val scope = DefaultApplicationScope(backgroundScope)
        val gate = CompletableDeferred<Unit>()
        var runs = 0
        val enqueuer = IosAnnotationSweepEnqueuer(scope) {
            runs++
            gate.await()
            CycleOutcome.Success(1L)
        }

        enqueuer.enqueue()
        testScheduler.runCurrent()
        enqueuer.enqueue()
        enqueuer.enqueue()
        testScheduler.runCurrent()
        assertEquals(1, runs, "duplicates while in flight collapse into the running sweep")

        gate.complete(Unit)
        testScheduler.runCurrent()
        enqueuer.enqueue()
        testScheduler.runCurrent()
        assertEquals(2, runs, "once the first sweep finished a new request runs")
    }

    @Test
    fun aSweepThatThrowsDoesNotWedgeTheEnqueuer() = runTest {
        val scope = DefaultApplicationScope(backgroundScope)
        var runs = 0
        val enqueuer = IosAnnotationSweepEnqueuer(scope) {
            runs++
            error("boom")
        }

        enqueuer.enqueue()
        testScheduler.runCurrent()
        enqueuer.enqueue()
        testScheduler.runCurrent()

        assertEquals(2, runs)
    }
}
