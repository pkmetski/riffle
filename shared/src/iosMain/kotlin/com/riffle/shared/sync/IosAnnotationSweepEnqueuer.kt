package com.riffle.shared.sync

import com.riffle.core.domain.AnnotationSweepEnqueuer
import com.riffle.core.domain.ApplicationScope
import com.riffle.core.sync.CycleOutcome
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * iOS [AnnotationSweepEnqueuer] (#1101). Android hands the sweep to WorkManager with
 * `ExistingWorkPolicy.KEEP`; iOS has no background executor (no `BGTaskScheduler` registration —
 * see the `ForegroundSyncDriver` note in `Koin.kt`), so the sweep runs on the survivable
 * [ApplicationScope] right away.
 *
 * One mutex serialises every way the sweep can start: [enqueue] (fire-and-forget, KEEP
 * semantics — a request while a sweep is in flight collapses into it) and [runNow] (awaited by
 * `ForegroundSyncDriver` at app start / foreground / reconnect). Without the shared guard the
 * two entry points could snapshot the same dirty rows and push each file twice.
 */
class IosAnnotationSweepEnqueuer(
    private val scope: ApplicationScope,
    private val runSweep: suspend () -> CycleOutcome?,
) : AnnotationSweepEnqueuer {

    private val inFlight = Mutex()

    override fun enqueue() {
        if (!inFlight.tryLock()) return
        val job = scope.launchSurvivable {
            try {
                runSweep()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // AnnotationSweep absorbs transport failures itself; anything else is logged by
                // the sweep's status store. Never let it take the survivable scope down.
            }
        }
        // Released on completion rather than inside the block: if the scope is already cancelled
        // the block never runs, and the lock must not be held forever.
        job.invokeOnCompletion { inFlight.unlock() }
    }

    /** Runs the sweep now and waits for it, serialised against any in-flight [enqueue]. */
    suspend fun runNow(): CycleOutcome? = inFlight.withLock { runSweep() }
}
