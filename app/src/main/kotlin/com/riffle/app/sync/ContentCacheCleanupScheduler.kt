package com.riffle.app.sync

import android.content.Context
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

object ContentCacheCleanupScheduler {
    private const val UNIQUE_SWEEP_TAG = "content-cache-cleanup"
    private const val UNIQUE_PERIODIC_TAG = "content-cache-cleanup-periodic"

    fun sweepNow(context: Context) {
        val request = OneTimeWorkRequestBuilder<ContentCacheCleanupWorker>()
            .addTag(UNIQUE_SWEEP_TAG)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            UNIQUE_SWEEP_TAG,
            ExistingWorkPolicy.KEEP,
            request,
        )
    }

    fun ensurePeriodic(context: Context) {
        val request = PeriodicWorkRequestBuilder<ContentCacheCleanupWorker>(1, TimeUnit.DAYS)
            .addTag(UNIQUE_PERIODIC_TAG)
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            UNIQUE_PERIODIC_TAG,
            ExistingPeriodicWorkPolicy.KEEP,
            request,
        )
    }
}
