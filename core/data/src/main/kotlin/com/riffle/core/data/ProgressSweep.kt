package com.riffle.core.data

import com.riffle.core.domain.ProgressReconciler
import com.riffle.core.domain.ProgressRemote
import com.riffle.core.domain.RemoteKind
import com.riffle.core.domain.Source

/** Enumerates the dirty position rows (localUpdatedAt > lastSyncedAt) the sweep must reconcile. */
interface DirtyProgressLedger {
    suspend fun serversWithDirty(): List<String>
    suspend fun dirtyEbookItems(sourceId: String): List<String>
    suspend fun dirtyAudioItems(sourceId: String): List<String>
}

/** Resolves a sourceId to its [Source] + auth token, or `null` when the source is unknown or has no
 *  valid token — in which case the sweep skips it and its rows stay dirty for a later run. */
fun interface ServerTokenResolver {
    suspend fun resolve(sourceId: String): Pair<Source, String>?
}

/** Builds the per-target ABS [ProgressRemote] for one (source, item). */
interface ProgressRemoteFactory {
    fun ebook(source: Source, token: String, itemId: String): ProgressRemote<String>
    fun audio(source: Source, token: String, itemId: String): ProgressRemote<Double>
}

/** Enumerates the (source, item) pairs with at least one dirty audiobook-bookmark row to reconcile. */
interface DirtyBookmarkLedger {
    suspend fun serversWithDirty(): List<String>
    suspend fun dirtyItems(sourceId: String): List<String>
}

/**
 * The set-reconcile of one item's audiobook bookmarks against ABS — the sweep's seam over
 * `AudiobookBookmarkReconciler` so its orchestration stays testable without Room or the network.
 */
fun interface BookmarkReconcile {
    suspend fun reconcile(sourceId: String, itemId: String, baseUrl: String, token: String, insecureAllowed: Boolean)
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
        // Bookmarks ride the same cadence as positions: a source with only dirty bookmarks (no dirty
        // positions) must still be reconciled, so process the union of both dirty sets (ADR 0030).
        val sources = (ledger.serversWithDirty() + bookmarkLedger.serversWithDirty()).distinct()
        for (sourceId in sources) {
            val (source, token) = resolver.resolve(sourceId) ?: continue
            for (itemId in ledger.dirtyEbookItems(sourceId)) {
                // Skip a book a live surface is driving — its own cycle owns inbound jumps (ADR 0030).
                if (openTargets.isOpen(sourceId, itemId)) continue
                locks.withLock(sourceId, itemId, RemoteKind.ABS_EBOOK) {
                    ebookReconciler.reconcile(sourceId, itemId, remoteFactory.ebook(source, token, itemId))
                }
            }
            for (itemId in ledger.dirtyAudioItems(sourceId)) {
                if (openTargets.isOpen(sourceId, itemId)) continue
                locks.withLock(sourceId, itemId, RemoteKind.ABS_AUDIO) {
                    audioReconciler.reconcile(sourceId, itemId, remoteFactory.audio(source, token, itemId))
                }
            }
            for (itemId in bookmarkLedger.dirtyItems(sourceId)) {
                // Same open-book discipline as the audio pass: an open book's bookmarks reconcile once
                // it closes. ABS_BOOKMARK is its own lock kind, so it never contends with the position
                // passes for the same item.
                if (openTargets.isOpen(sourceId, itemId)) continue
                locks.withLock(sourceId, itemId, RemoteKind.ABS_BOOKMARK) {
                    bookmarkReconcile.reconcile(
                        sourceId, itemId, source.url.value, token, source.insecureConnectionAllowed,
                    )
                }
            }
        }
    }
}
