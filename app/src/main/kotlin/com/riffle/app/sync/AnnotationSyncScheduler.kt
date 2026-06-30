package com.riffle.app.sync

import android.content.Context

/**
 * Schedules the durable annotation sweep (ADR 0036). Thin facade over the shared [SyncScheduler].
 */
object AnnotationSyncScheduler {

    private val impl = SyncScheduler(
        AnnotationSyncWorker::class.java,
        uniqueSweepTag = "annotation-sync-sweep",
        uniquePeriodicTag = "annotation-sync-sweep-periodic",
    )

    fun sweepNow(context: Context) = impl.sweepNow(context)

    fun ensurePeriodic(context: Context) = impl.ensurePeriodic(context)
}
