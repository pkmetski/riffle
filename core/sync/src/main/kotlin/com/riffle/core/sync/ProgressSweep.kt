package com.riffle.core.sync

import com.riffle.core.catalog.AudiobookProgressPeerCapability
import com.riffle.core.catalog.CatalogRegistry
import com.riffle.core.catalog.ProgressPeerCapability
import com.riffle.core.domain.ProgressReconciler
import com.riffle.core.domain.RemoteKind

/**
 * The durable, book-independent dirty sweep of ADR 0036: reconcile every dirty position row across
 * all sources when online, so offline progress is pushed without the book being reopened.
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
        val sources = (ledger.serversWithDirty() + bookmarkLedger.serversWithDirty()).distinct()
        for (sourceId in sources) {
            val catalog = catalogRegistry.forSourceId(sourceId) ?: continue
            val isProgressPeer = catalog is ProgressPeerCapability
            val isAudioPeer = catalog is AudiobookProgressPeerCapability
            if (isProgressPeer) for (itemId in ledger.dirtyEbookItems(sourceId)) {
                if (openTargets.isOpen(sourceId, itemId)) continue
                val remote = remoteFactory.ebook(sourceId, itemId) ?: continue
                locks.withLock(sourceId, itemId, RemoteKind.EBOOK_POSITION) {
                    ebookReconciler.reconcile(sourceId, itemId, remote)
                }
            }
            if (isAudioPeer) for (itemId in ledger.dirtyAudioItems(sourceId)) {
                if (openTargets.isOpen(sourceId, itemId)) continue
                val remote = remoteFactory.audio(sourceId, itemId) ?: continue
                locks.withLock(sourceId, itemId, RemoteKind.AUDIO_POSITION) {
                    audioReconciler.reconcile(sourceId, itemId, remote)
                }
            }
            for (itemId in bookmarkLedger.dirtyItems(sourceId)) {
                if (openTargets.isOpen(sourceId, itemId)) continue
                locks.withLock(sourceId, itemId, RemoteKind.AUDIOBOOK_BOOKMARK) {
                    bookmarkReconcile.reconcile(sourceId, itemId)
                }
            }
        }
    }
}
