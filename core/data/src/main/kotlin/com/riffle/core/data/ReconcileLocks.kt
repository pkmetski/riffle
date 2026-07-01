package com.riffle.core.data

import com.riffle.core.domain.RemoteKind
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Per-resource reconcile mutexes (#321). Held by background sweeps and the live reader/player so a
 * given remote resource is reconciled by exactly one of them at a time — closing the "worker fires
 * just as the book is open" double-push race. A process-wide singleton.
 *
 * Two key shapes are exposed because the resource axis differs per pipeline:
 *
 * - Progress (ADR 0030): `(serverId, itemId, kind)` — three peer-target axes per book.
 * - Annotations (ADR 0036): `(serverId, itemId)` — one device file per book, no per-target axis.
 *
 * The two axes use separate maps, so an annotation push and a progress reconcile on the same
 * `(server, item)` do not contend with each other.
 */
@Singleton
class ReconcileLocks @Inject constructor() : AnnotationLockPort {
    private val progressMutexes = ConcurrentHashMap<String, Mutex>()
    private val annotationMutexes = ConcurrentHashMap<String, Mutex>()

    /** Progress reconcile lock — per `(serverId, itemId, kind)`. */
    suspend fun <T> withLock(serverId: String, itemId: String, kind: RemoteKind, block: suspend () -> T): T {
        val mutex = progressMutexes.getOrPut("$serverId $itemId $kind") { Mutex() }
        return mutex.withLock { block() }
    }

    /** Annotation reconcile lock — per `(serverId, itemId)`. Closes the torn-write window between
     *  the live [AnnotationSyncController] push and the durable [AnnotationSweep] push. */
    override suspend fun <T> withAnnotationLock(serverId: String, itemId: String, block: suspend () -> T): T {
        val mutex = annotationMutexes.getOrPut("$serverId $itemId") { Mutex() }
        return mutex.withLock { block() }
    }
}
