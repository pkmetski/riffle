package com.riffle.core.domain

/**
 * Seam used by [com.riffle.core.data.AnnotationSyncController] to request a WorkManager-backed
 * sweep on push failure, without `core:data` taking a WorkManager dependency. The app module
 * provides the concrete implementation that enqueues `AnnotationSyncScheduler.sweepNow(context)`.
 */
fun interface AnnotationSweepEnqueuer {
    /** Coalesced; safe to call repeatedly — the underlying enqueueUniqueWork is KEEP. */
    fun enqueue()
}
