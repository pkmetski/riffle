package com.riffle.app.sync

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.riffle.core.sync.ProgressSweep
import org.koin.core.context.GlobalContext

/**
 * Thin WorkManager shell over the tested [ProgressSweep] (ADR 0036): runs the durable, book-
 * independent dirty reconcile when the device is online. Dependencies are resolved via Koin.
 * A network failure mid-sweep leaves the affected rows dirty, so retrying the whole sweep is safe
 * and idempotent.
 */
class ProgressSyncWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result =
        try {
            GlobalContext.get().get<ProgressSweep>().run()
            Result.success()
        } catch (e: Exception) {
            // Transient failure (network blip, etc.) — dirty rows are untouched; retry the sweep.
            Result.retry()
        }
}
