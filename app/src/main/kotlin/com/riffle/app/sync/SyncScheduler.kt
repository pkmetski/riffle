package com.riffle.app.sync

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.PeriodicWorkRequest
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

/**
 * Generic sweep + periodic-safety-net scheduler shared by every sync pipeline (#321). Parameterized
 * over the [CoroutineWorker] class and the unique-work tags so each pipeline gets its own queue while
 * the WorkManager request shape (CONNECTED constraint, KEEP coalescing, exponential backoff, 1h
 * safety-net cadence) lives in exactly one place. ADRs 0030 (progress) and 0036 (annotations).
 */
class SyncScheduler<W : CoroutineWorker>(
    private val workerClass: Class<W>,
    private val uniqueSweepTag: String,
    private val uniquePeriodicTag: String,
) {
    private val onlineConstraint =
        Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    /** Enqueue a one-shot sweep (coalesced via KEEP). */
    fun sweepNow(context: Context) {
        val request = OneTimeWorkRequest.Builder(workerClass)
            .setConstraints(onlineConstraint)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context)
            .enqueueUniqueWork(uniqueSweepTag, ExistingWorkPolicy.KEEP, request)
    }

    /** Register the periodic safety-net sweep (idempotent). */
    fun ensurePeriodic(context: Context) {
        val request = PeriodicWorkRequest.Builder(workerClass, 1, TimeUnit.HOURS)
            .setConstraints(onlineConstraint)
            .build()
        WorkManager.getInstance(context)
            .enqueueUniquePeriodicWork(uniquePeriodicTag, ExistingPeriodicWorkPolicy.KEEP, request)
    }
}
