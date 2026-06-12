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
 * Schedules the durable progress sweep (ADR 0030). All requests carry a CONNECTED constraint, so a
 * sweep enqueued while offline simply waits until connectivity returns. [sweepNow] coalesces via
 * unique work (KEEP), so many dirty-marks collapse to a single pending sweep; [ensurePeriodic] is the
 * safety net for progress made while the app was never reopened.
 */
object ProgressSyncScheduler {

    private const val UNIQUE_SWEEP = "progress-sync-sweep"
    private const val UNIQUE_PERIODIC = "progress-sync-sweep-periodic"

    private val onlineConstraint =
        Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    /** Enqueue a one-shot sweep (coalesced). Call on a local progress change / connectivity regain. */
    fun sweepNow(context: Context) {
        val request = OneTimeWorkRequestBuilder<ProgressSyncWorker>()
            .setConstraints(onlineConstraint)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context)
            .enqueueUniqueWork(UNIQUE_SWEEP, ExistingWorkPolicy.KEEP, request)
    }

    /** Register the periodic safety-net sweep (idempotent). */
    fun ensurePeriodic(context: Context) {
        val request = PeriodicWorkRequestBuilder<ProgressSyncWorker>(1, TimeUnit.HOURS)
            .setConstraints(onlineConstraint)
            .build()
        WorkManager.getInstance(context)
            .enqueueUniquePeriodicWork(UNIQUE_PERIODIC, ExistingPeriodicWorkPolicy.KEEP, request)
    }
}
