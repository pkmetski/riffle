package com.riffle.app.sync

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ListenableWorker
import androidx.work.WorkerParameters
import com.riffle.core.data.AnnotationSweep
import com.riffle.core.data.CycleOutcome
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CancellationException

/**
 * Thin WorkManager shell over [AnnotationSweep] (ADR 0036). Dependencies via a Hilt EntryPoint
 * (no @HiltWorker / custom factory).
 *
 * Maps the sweep's [CycleOutcome] to a [Result] so WorkManager's exponential backoff actually
 * re-fires after transient failures — and so the retry job carries the CONNECTED constraint that
 * makes "go back online → sync resumes" a property of the system, not of the foreground app.
 * The previous shape unconditionally returned [Result.success], which is why a stale "will retry
 * automatically" banner could sit there until the periodic 1-hour tick.
 */
class AnnotationSyncWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface SweepEntryPoint {
        fun annotationSweep(): AnnotationSweep
    }

    override suspend fun doWork(): Result =
        try {
            val outcome = EntryPointAccessors
                .fromApplication(applicationContext, SweepEntryPoint::class.java)
                .annotationSweep()
                .run()
            outcomeToResult(outcome)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Anything reaching here is a programming error — AnnotationSweep absorbs all transport
            // failures itself. Don't retry.
            Result.failure()
        }
}

/**
 * Translate a [CycleOutcome] into a WorkManager [Result]. Transient transport failures retry so
 * WorkManager's CONNECTED-constrained backoff re-fires when the network returns; credential/TLS
 * failures need user action and exit the queue.
 */
internal fun outcomeToResult(outcome: CycleOutcome?): ListenableWorker.Result = when (outcome) {
    null, // unconfigured target: nothing to do
    is CycleOutcome.NeverRun, // unreachable from the sweep, defensive
    is CycleOutcome.Success -> ListenableWorker.Result.success()
    is CycleOutcome.Failed.Network,
    is CycleOutcome.Failed.Server,
    is CycleOutcome.Failed.Unknown -> ListenableWorker.Result.retry()
    is CycleOutcome.Failed.Auth,
    is CycleOutcome.Failed.Tls -> ListenableWorker.Result.failure()
}
