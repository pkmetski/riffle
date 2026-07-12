package com.riffle.core.data

import com.riffle.core.catalog.AudiobookProgressPeerCapability
import com.riffle.core.catalog.CatalogRegistry
import com.riffle.core.catalog.ProgressPeerCapability
import com.riffle.core.domain.ProgressReconciler
import com.riffle.core.domain.ProgressRemote
import com.riffle.core.domain.RemoteKind

/** Enumerates the dirty position rows (localUpdatedAt > lastSyncedAt) the sweep must reconcile. */
interface DirtyProgressLedger {
    suspend fun serversWithDirty(): List<String>
    suspend fun dirtyEbookItems(sourceId: String): List<String>
    suspend fun dirtyAudioItems(sourceId: String): List<String>
}

/** Builds the per-target [ProgressRemote] for one (sourceId, itemId), or null when unavailable. */
interface ProgressRemoteFactory {
    suspend fun ebook(sourceId: String, itemId: String): ProgressRemote<String>?
    suspend fun audio(sourceId: String, itemId: String): ProgressRemote<Double>?
}

/** Enumerates the (source, item) pairs with at least one dirty audiobook-bookmark row to reconcile. */
interface DirtyBookmarkLedger {
    suspend fun serversWithDirty(): List<String>
    suspend fun dirtyItems(sourceId: String): List<String>
}

/**
 * The set-reconcile of one item's audiobook bookmarks against the Source's bookmarks capability —
 * the sweep's seam over `AudiobookBookmarkReconciler` so its orchestration stays testable without
 * Room or the network.
 */
fun interface BookmarkReconcile {
    suspend fun reconcile(sourceId: String, itemId: String)
}

/**
 * The durable, book-independent dirty sweep of ADR 0030: reconcile every dirty position row across
 * **all** sources when online, so offline progress is pushed without the book being reopened — while
 * a newer server position still wins (each reconcile is GET-before-PATCH). Single-target: it pushes
 * one media record per dirty row, never translating. Each target reconciles under its per-target
 * lock (shared with the live paths) so the worker and an open book never double-reconcile the same
 * target.
 */
class ProgressSweep(
    private val ledger: DirtyProgressLedger,
    private val catalogRegistry: CatalogRegistry,
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
            // Skip sources whose catalog can't be resolved right now (no row, no token, or no
            // registered factory) — rows stay dirty for the next sweep.
            val catalog = catalogRegistry.forSourceId(sourceId) ?: continue
            // Progress reconciliation is only defined for sources whose Catalog is a
            // ProgressPeerCapability (ADR 0041). Zero-peer sources (LocalFiles) still get their
            // dirty position rows enumerated, but the loops below no-op — the rows are legal
            // zero-peer entries and drain without work. Bookmarks are gated separately, further
            // down, on their own capability by the underlying reconciler.
            val isProgressPeer = catalog is ProgressPeerCapability
            val isAudioPeer = catalog is AudiobookProgressPeerCapability
            if (isProgressPeer) for (itemId in ledger.dirtyEbookItems(sourceId)) {
                // Skip a book a live surface is driving — its own cycle owns inbound jumps (ADR 0030).
                if (openTargets.isOpen(sourceId, itemId)) continue
                val remote = remoteFactory.ebook(sourceId, itemId) ?: continue
                locks.withLock(sourceId, itemId, RemoteKind.EBOOK_POSITION) {
                    ebookReconciler.reconcile(sourceId, itemId, remote)
                }
            }
            // Audio pass is gated on AudiobookProgressPeerCapability (#528): ebook-only peers
            // (Komga) never have dirty audio rows, but gate explicitly so a stale row from a
            // deleted/downgraded source can't accidentally trigger a doomed PATCH.
            if (isAudioPeer) for (itemId in ledger.dirtyAudioItems(sourceId)) {
                if (openTargets.isOpen(sourceId, itemId)) continue
                val remote = remoteFactory.audio(sourceId, itemId) ?: continue
                locks.withLock(sourceId, itemId, RemoteKind.AUDIO_POSITION) {
                    audioReconciler.reconcile(sourceId, itemId, remote)
                }
            }
            for (itemId in bookmarkLedger.dirtyItems(sourceId)) {
                // Same open-book discipline as the audio pass: an open book's bookmarks reconcile
                // once it closes. AUDIOBOOK_BOOKMARK is its own lock kind, so it never contends
                // with the position passes for the same item.
                if (openTargets.isOpen(sourceId, itemId)) continue
                locks.withLock(sourceId, itemId, RemoteKind.AUDIOBOOK_BOOKMARK) {
                    bookmarkReconcile.reconcile(sourceId, itemId)
                }
            }
        }
    }
}
