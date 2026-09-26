package com.riffle.shared.sync

import com.riffle.core.domain.AnnotationSweepEnqueuer
import com.riffle.core.domain.ApplicationScope
import com.riffle.core.sync.CycleOutcome
import kotlinx.coroutines.sync.Mutex

/**
 * iOS [AnnotationSweepEnqueuer] (#1101). Android hands the sweep to WorkManager with
 * `ExistingWorkPolicy.KEEP`; iOS has no background executor (no `BGTaskScheduler` registration —
 * see the `ForegroundSyncDriver` note in `Koin.kt`), so the sweep runs on the survivable
 * [ApplicationScope] right away. KEEP semantics are kept: while one sweep is in flight, further
 * `enqueue()` calls collapse into it instead of stacking a second run behind the same rows.
 */
class IosAnnotationSweepEnqueuer(
    private val scope: ApplicationScope,
    private val runSweep: suspend () -> CycleOutcome?,
) : AnnotationSweepEnqueuer {

    private val inFlight = Mutex()

    override fun enqueue() {
        if (!inFlight.tryLock()) return
        scope.launchSurvivable {
            try {
                runCatching { runSweep() }
            } finally {
                inFlight.unlock()
            }
        }
    }
}
