package com.riffle.core.domain

/**
 * Seam used by call sites that need to kick a WorkManager-backed progress sweep without taking a
 * WorkManager or Android Context dependency. The app module provides the concrete implementation
 * that calls [com.riffle.app.sync.ProgressSyncScheduler.sweepNow].
 */
fun interface ProgressSweepEnqueuer {
    /** Coalesced; safe to call repeatedly — the underlying enqueueUniqueWork is KEEP. */
    fun enqueue()
}
