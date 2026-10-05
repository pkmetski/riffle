package com.riffle.app.sync

import android.content.Context
import androidx.work.CoroutineWorker
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
 * should not retry those.
 */
class PlaylistSyncWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result =
        try {
            GlobalContext.get().get<PlaylistSweep>().run()
            Result.success()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            Result.failure()
        }
}
