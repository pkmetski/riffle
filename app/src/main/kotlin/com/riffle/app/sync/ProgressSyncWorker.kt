package com.riffle.app.sync

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.riffle.core.data.ProgressSweep
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent

/**
 * Thin WorkManager shell over the tested [ProgressSweep] (ADR 0030): runs the durable, book-
 * independent dirty reconcile when the device is online. Dependencies are resolved via a Hilt
 * EntryPoint (no @HiltWorker / custom factory needed). A network failure mid-sweep leaves the
 * affected rows dirty, so retrying the whole sweep is safe and idempotent.
 */
class ProgressSyncWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface SweepEntryPoint {
        fun progressSweep(): ProgressSweep
    }

    override suspend fun doWork(): Result =
        try {
            EntryPointAccessors.fromApplication(applicationContext, SweepEntryPoint::class.java)
                .progressSweep()
                .run()
            Result.success()
        } catch (e: Exception) {
            // Transient failure (network blip, etc.) — dirty rows are untouched; retry the sweep.
            Result.retry()
        }
}
