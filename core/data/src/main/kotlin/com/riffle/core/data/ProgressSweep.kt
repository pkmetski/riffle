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

/** Enumerates the (server, item) pairs with at least one dirty audiobook-bookmark row to reconcile. */
interface DirtyBookmarkLedger {
    suspend fun serversWithDirty(): List<String>
    suspend fun dirtyItems(serverId: String): List<String>
}

/**
 * The set-reconcile of one item's audiobook bookmarks against ABS — the sweep's seam over
 * `AudiobookBookmarkReconciler` so its orchestration stays testable without Room or the network.
 */
fun interface BookmarkReconcile {
    suspend fun reconcile(serverId: String, itemId: String, baseUrl: String, token: String, insecureAllowed: Boolean)
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
    private val locks: ReconcileLocks,
    private val openTargets: OpenReconcileTargets,
    private val bookmarkLedger: DirtyBookmarkLedger,
    private val bookmarkReconcile: BookmarkReconcile,
) {
    suspend fun run() {
        // Bookmarks ride the same cadence as positions: a server with only dirty bookmarks (no dirty
        // positions) must still be reconciled, so process the union of both dirty sets (ADR 0030).
        val servers = (ledger.serversWithDirty() + bookmarkLedger.serversWithDirty()).distinct()
        for (serverId in servers) {
            val (server, token) = resolver.resolve(serverId) ?: continue
            for (itemId in ledger.dirtyEbookItems(serverId)) {
                // Skip a book a live surface is driving — its own cycle owns inbound jumps (ADR 0030).
                if (openTargets.isOpen(serverId, itemId)) continue
                locks.withLock(serverId, itemId, RemoteKind.ABS_EBOOK) {
                    ebookReconciler.reconcile(serverId, itemId, remoteFactory.ebook(server, token, itemId))
                }
            }
            for (itemId in ledger.dirtyAudioItems(serverId)) {
                if (openTargets.isOpen(serverId, itemId)) continue
                locks.withLock(serverId, itemId, RemoteKind.ABS_AUDIO) {
                    audioReconciler.reconcile(serverId, itemId, remoteFactory.audio(server, token, itemId))
                }
            }
            for (itemId in bookmarkLedger.dirtyItems(serverId)) {
                // Same open-book discipline as the audio pass: an open book's bookmarks reconcile once
                // it closes. ABS_BOOKMARK is its own lock kind, so it never contends with the position
                // passes for the same item.
                if (openTargets.isOpen(serverId, itemId)) continue
                locks.withLock(serverId, itemId, RemoteKind.ABS_BOOKMARK) {
                    bookmarkReconcile.reconcile(
                        serverId, itemId, server.url.value, token, server.insecureConnectionAllowed,
                    )
                }
            }
        }
    }
}
