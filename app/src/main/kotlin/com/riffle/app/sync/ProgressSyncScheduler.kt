package com.riffle.app.sync

import android.content.Context

/**
 * Schedules the durable progress sweep (ADR 0030). Thin facade over the shared [SyncScheduler].
 * Public `sweepNow` / `ensurePeriodic` shape is preserved so call sites in [RiffleApplication] and
 * other places remain unchanged.
 */
object ProgressSyncScheduler {

    private val impl = SyncScheduler(
        ProgressSyncWorker::class.java,
        uniqueSweepTag = "progress-sync-sweep",
        uniquePeriodicTag = "progress-sync-sweep-periodic",
    )

    /** Enqueue a one-shot sweep (coalesced). Call on a local progress change / connectivity regain. */
    fun sweepNow(context: Context) = impl.sweepNow(context)

    /** Register the periodic safety-net sweep (idempotent). */
    fun ensurePeriodic(context: Context) = impl.ensurePeriodic(context)
}
