package com.riffle.app.sync

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.riffle.core.data.AnnotationSweep
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CancellationException

/**
 * Thin WorkManager shell over [AnnotationSweep] (ADR 0036). Mirrors [ProgressSyncWorker]:
 * dependencies via a Hilt EntryPoint (no @HiltWorker / custom factory). A transient failure
 * inside the sweep leaves rows dirty, so Result.retry is safe and idempotent.
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
            EntryPointAccessors.fromApplication(applicationContext, SweepEntryPoint::class.java)
                .annotationSweep()
                .run()
            Result.success()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Anything reaching here is a programming error — AnnotationSweep absorbs all transport
            // failures itself. Don't retry; report a permanent failure so WorkManager stops
            // scheduling backoff retries.
            Result.failure()
        }
}
