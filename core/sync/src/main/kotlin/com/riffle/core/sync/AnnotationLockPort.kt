package com.riffle.core.sync

/**
 * Per-book annotation-reconcile lock seam (ADR 0036). Held across the live push and the durable
 * [com.riffle.core.sync.AnnotationSweep] so a given device file is reconciled by exactly one
 * writer at a time.
 */
interface AnnotationLockPort {
    suspend fun <T> withAnnotationLock(sourceId: String, itemId: String, block: suspend () -> T): T
}
