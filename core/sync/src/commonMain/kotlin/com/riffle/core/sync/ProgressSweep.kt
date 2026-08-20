package com.riffle.core.sync

import com.riffle.core.domain.ProgressReconciler
import com.riffle.core.domain.RemoteKind

/**
 * The durable, book-independent dirty sweep of ADR 0036: reconcile every dirty position row across
 * all sources when online, so offline progress is pushed without the book being reopened.
 *
 * [remoteIndex] supplements the dirty-row ledger: for WebDAV-backed sources it provides all items
 * that exist on the server (not just locally-dirty ones), so a clean row that a second device
 * advanced on the server is pulled back even without a local edit triggering the dirty flag.
 * Server sources (ABS, Komga) are not affected — they do not appear in [remoteIndex].
 */
class ProgressSweep(
    private val ledger: DirtyProgressLedger,
    private val sourceResolver: SyncSourceResolver,
    private val ebookReconciler: ProgressReconciler<String>,
    private val audioReconciler: ProgressReconciler<Double>,
    private val remoteFactory: ProgressRemoteFactory,
    private val locks: ReconcileLocks,
    private val openTargets: OpenReconcileTargets,
    private val bookmarkLedger: DirtyBookmarkLedger,
    private val bookmarkReconcile: BookmarkReconcile,
    private val remoteIndex: RemoteProgressIndex = RemoteProgressIndex.EMPTY,
) {
    suspend fun run() {
        val sources = (ledger.serversWithDirty() + bookmarkLedger.serversWithDirty() + remoteIndex.sourcesWithRemote()).distinct()
        for (sourceId in sources) {
            val source = sourceResolver.resolve(sourceId) ?: continue
            if (source.supportsEbookProgress) {
                val items = (ledger.dirtyEbookItems(sourceId) + remoteIndex.remoteEbookItems(sourceId)).distinct()
                for (itemId in items) {
                    if (openTargets.isOpen(sourceId, itemId)) continue
                    val remote = remoteFactory.ebook(sourceId, itemId) ?: continue
                    locks.withLock(sourceId, itemId, RemoteKind.EBOOK_POSITION) {
                        ebookReconciler.reconcile(sourceId, itemId, remote)
                    }
                }
            }
            if (source.supportsAudiobookProgress) {
                val items = (ledger.dirtyAudioItems(sourceId) + remoteIndex.remoteAudioItems(sourceId)).distinct()
                for (itemId in items) {
                    if (openTargets.isOpen(sourceId, itemId)) continue
                    val remote = remoteFactory.audio(sourceId, itemId) ?: continue
                    locks.withLock(sourceId, itemId, RemoteKind.AUDIO_POSITION) {
                        audioReconciler.reconcile(sourceId, itemId, remote)
                    }
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
