package com.riffle.app.sync

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

/**
 * Schedules the durable annotation sweep (ADR 0036). Mirrors [ProgressSyncScheduler]: CONNECTED
 * constraint defers offline requests until wifi returns; sweepNow coalesces via unique work (KEEP);
 * ensurePeriodic is the safety net for failed pushes after process death.
 */
object AnnotationSyncScheduler {

    private const val UNIQUE_SWEEP = "annotation-sync-sweep"
    private const val UNIQUE_PERIODIC = "annotation-sync-sweep-periodic"

    private val onlineConstraint =
        Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    fun sweepNow(context: Context) {
        val request = OneTimeWorkRequestBuilder<AnnotationSyncWorker>()
            .setConstraints(onlineConstraint)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context)
            .enqueueUniqueWork(UNIQUE_SWEEP, ExistingWorkPolicy.KEEP, request)
    }

    fun ensurePeriodic(context: Context) {
        val request = PeriodicWorkRequestBuilder<AnnotationSyncWorker>(1, TimeUnit.HOURS)
            .setConstraints(onlineConstraint)
            .build()
        WorkManager.getInstance(context)
            .enqueueUniquePeriodicWork(UNIQUE_PERIODIC, ExistingPeriodicWorkPolicy.KEEP, request)
    }
}
