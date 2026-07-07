package com.riffle.core.data

/**
 * Per-book annotation-reconcile lock seam (ADR 0036). Held across the live push and the durable
 * [AnnotationSweep] so a given device file is reconciled by exactly one writer at a time.
 *
 * Introduced to break the direct dependency on [ReconcileLocks] — test doubles now implement this
 * port instead of instantiating the process-wide concrete class.
 */
interface AnnotationLockPort {
    suspend fun <T> withAnnotationLock(sourceId: String, itemId: String, block: suspend () -> T): T
}
