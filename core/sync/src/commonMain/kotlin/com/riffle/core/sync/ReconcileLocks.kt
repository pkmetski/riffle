package com.riffle.core.sync

import com.riffle.core.domain.RemoteKind
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Per-resource reconcile mutexes (#321). Held by background sweeps and the live reader/player so a
 * given remote resource is reconciled by exactly one of them at a time — closing the
 * "worker fires just as the book is open" double-push race. A process-wide singleton.
 *
 * Two key shapes are exposed because the resource axis differs per pipeline:
 *
 * - Progress (ADR 0036): `(sourceId, itemId, kind)` — three peer-target axes per book.
 * - Annotations (ADR 0043): `(sourceId, itemId)` — one device file per book, no per-target axis.
 */
class ReconcileLocks : AnnotationLockPort {
    private val registryMutex = Mutex()
    private val progressMutexes = mutableMapOf<String, Mutex>()
    private val annotationMutexes = mutableMapOf<String, Mutex>()

    /** Progress reconcile lock — per `(sourceId, itemId, kind)`. */
    suspend fun <T> withLock(sourceId: String, itemId: String, kind: RemoteKind, block: suspend () -> T): T {
        val mutex = mutexFor(progressMutexes, "$sourceId $itemId $kind")
        return mutex.withLock { block() }
    }

    /** Annotation reconcile lock — per `(sourceId, itemId)`. */
    override suspend fun <T> withAnnotationLock(sourceId: String, itemId: String, block: suspend () -> T): T {
        val mutex = mutexFor(annotationMutexes, "$sourceId $itemId")
        return mutex.withLock { block() }
    }

    private suspend fun mutexFor(registry: MutableMap<String, Mutex>, key: String): Mutex =
        registryMutex.withLock { registry.getOrPut(key) { Mutex() } }
}
