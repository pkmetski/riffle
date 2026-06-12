package com.riffle.core.data

import com.riffle.core.domain.ProgressReconciler
import com.riffle.core.domain.ProgressRemote
import com.riffle.core.domain.RemoteKind
import com.riffle.core.domain.Server

/** Enumerates the dirty position rows (localUpdatedAt > lastSyncedAt) the sweep must reconcile. */
interface DirtyProgressLedger {
    suspend fun serversWithDirty(): List<String>
    suspend fun dirtyEbookItems(serverId: String): List<String>
    suspend fun dirtyAudioItems(serverId: String): List<String>
}

/** Resolves a serverId to its [Server] + auth token, or `null` when the server is unknown or has no
 *  valid token — in which case the sweep skips it and its rows stay dirty for a later run. */
fun interface ServerTokenResolver {
    suspend fun resolve(serverId: String): Pair<Server, String>?
}

/** Builds the per-target ABS [ProgressRemote] for one (server, item). */
interface ProgressRemoteFactory {
    fun ebook(server: Server, token: String, itemId: String): ProgressRemote<String>
    fun audio(server: Server, token: String, itemId: String): ProgressRemote<Double>
}

/**
 * The durable, book-independent dirty sweep of ADR 0030: reconcile every dirty position row across
 * **all** servers when online, so offline progress is pushed without the book being reopened — while
 * a newer server position still wins (each reconcile is GET-before-PATCH). Single-target: it pushes
 * one ABS record per dirty row, never translating. Each target reconciles under its per-target lock
 * (shared with the live paths) so the worker and an open book never double-reconcile the same target.
 */
class ProgressSweep(
    private val ledger: DirtyProgressLedger,
    private val resolver: ServerTokenResolver,
    private val ebookReconciler: ProgressReconciler<String>,
    private val audioReconciler: ProgressReconciler<Double>,
    private val remoteFactory: ProgressRemoteFactory,
    private val locks: ProgressSyncLocks,
) {
    suspend fun run() {
        for (serverId in ledger.serversWithDirty()) {
            val (server, token) = resolver.resolve(serverId) ?: continue
            for (itemId in ledger.dirtyEbookItems(serverId)) {
                locks.withLock(serverId, itemId, RemoteKind.ABS_EBOOK) {
                    ebookReconciler.reconcile(serverId, itemId, remoteFactory.ebook(server, token, itemId))
                }
            }
            for (itemId in ledger.dirtyAudioItems(serverId)) {
                locks.withLock(serverId, itemId, RemoteKind.ABS_AUDIO) {
                    audioReconciler.reconcile(serverId, itemId, remoteFactory.audio(server, token, itemId))
                }
            }
        }
    }
}
