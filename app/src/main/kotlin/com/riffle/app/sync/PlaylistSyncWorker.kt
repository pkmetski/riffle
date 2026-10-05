package com.riffle.app.sync

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ListenableWorker
import androidx.work.WorkerParameters
import com.riffle.core.data.PlaylistSweep
import kotlinx.coroutines.CancellationException
import org.koin.core.context.GlobalContext

/**
 * Thin WorkManager shell over [PlaylistSweep]. Mirrors the shape of [AnnotationSyncWorker]:
 * CONNECTED constraint, KEEP coalescing, exponential backoff, 1h periodic safety-net cadence.
 *
 * Called by [PlaylistSyncScheduler.sweepNow] (on-demand after a local To Read change or reconnect)
 * and by the periodic job registered in [PlaylistSyncScheduler.ensurePeriodic] (background safety
 * net so playlists sync even when the app is never foregrounded).
 *
 * Unlike progress/annotation sweeps, PlaylistSweep does not return a [CycleOutcome] — it absorbs
 * all transport failures itself. Any exception that escapes is a programming error; WorkManager
 * should not retry those. See [sweepExceptionToResult].
 */
class PlaylistSyncWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result =
        try {
            GlobalContext.get().get<PlaylistSweep>().run()
            ListenableWorker.Result.success()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            sweepExceptionToResult(e)
        }
}

/**
 * Maps an unexpected exception from [PlaylistSweep] to a WorkManager [Result].
 *
 * [PlaylistSweep] absorbs all transport failures (network, server, auth) itself, so anything
 * reaching here is a programming error. Return [ListenableWorker.Result.failure] — *not* retry —
 * so WorkManager removes the job from the queue instead of scheduling an exponential-backoff
 * retry for a condition that retrying cannot fix.
 *
 * Contrast with [AnnotationSyncWorker]'s [outcomeToResult], which maps transient network/server
 * failures to [ListenableWorker.Result.retry] because [com.riffle.core.data.AnnotationSweep] *does*
 * surface transport failures to callers.
 */
internal fun sweepExceptionToResult(@Suppress("UNUSED_PARAMETER") e: Exception): ListenableWorker.Result =
    ListenableWorker.Result.failure()
