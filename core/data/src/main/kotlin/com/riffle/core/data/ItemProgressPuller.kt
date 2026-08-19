package com.riffle.core.data

import com.riffle.core.catalog.AudiobookProgressPeerCapability
import com.riffle.core.catalog.CatalogRegistry
import com.riffle.core.domain.ProgressReconciler
import com.riffle.core.domain.RemoteKind
import com.riffle.core.sync.OpenReconcileTargets
import com.riffle.core.sync.ProgressRemoteFactory
import com.riffle.core.sync.ReconcileLocks
import javax.inject.Inject

/**
 * Runs the ADR-0036 GET-before-PATCH reconcile for one (sourceId, itemId) — the same primitive
 * [ProgressSweep] runs across dirty rows, but scoped to a specific book on demand. Invoked from
 * [com.riffle.app.feature.reader.session.ReaderSessionLifecycle.open] BEFORE loading the initial
 * locator so the reader opens at the fresh server position instead of rendering the old local
 * cfi first and then visibly jumping when the in-reader sync cycle catches up.
 *
 * Not wired into details-page ON_RESUME (only the reader-open moment) — an earlier attempt to run
 * this on every details visit correlated with a library-grid refresh regression whose cause was
 * never fully diagnosed; keeping the invocation surface narrow reduces the blast radius.
 *
 * Acquires the same per-target [ReconcileLocks] the sweep uses so this path and the sweep never
 * double-run on the same target. Deliberately does NOT gate on [OpenReconcileTargets.isOpen] —
 * the caller invokes it BEFORE [OpenReconcileTargets.markOpen], so the check would be a false
 * positive on the reader's own opening cycle only if the book was already marked open by another
 * live session, in which case we're happy to defer.
 */
interface ItemProgressPuller {
    suspend fun pullItem(sourceId: String, itemId: String)
}

class ReconcilingItemProgressPuller @Inject constructor(
    ebookStore: ReadingPositionStoreImpl,
    audioStore: AudiobookPositionStoreImpl,
    private val catalogRegistry: CatalogRegistry,
    private val remoteFactory: ProgressRemoteFactory,
    private val locks: ReconcileLocks,
    private val openTargets: OpenReconcileTargets,
    uiProgressSink: LibraryItemUiProgressSink,
) : ItemProgressPuller {
    private val ebookReconciler = ProgressReconciler(ebookStore, uiProgressSink)
    private val audioReconciler = ProgressReconciler(audioStore, uiProgressSink)

    override suspend fun pullItem(sourceId: String, itemId: String) {
        if (openTargets.isOpen(sourceId, itemId)) return
        val catalog = catalogRegistry.forSourceId(sourceId) ?: return
        // Ebook: the factory resolves both catalog-peer remotes (ABS, Komga) and WebDAV remotes
        // for web sources (ADR 0063). No need to check ProgressPeerCapability here — the factory
        // returns null when no remote is applicable.
        val ebookRemote = remoteFactory.ebook(sourceId, itemId)
        if (ebookRemote != null) locks.withLock(sourceId, itemId, RemoteKind.EBOOK_POSITION) {
            ebookReconciler.reconcile(sourceId, itemId, ebookRemote)
        }
        if (catalog is AudiobookProgressPeerCapability) {
            val audioRemote = remoteFactory.audio(sourceId, itemId)
            if (audioRemote != null) locks.withLock(sourceId, itemId, RemoteKind.AUDIO_POSITION) {
                audioReconciler.reconcile(sourceId, itemId, audioRemote)
            }
        }
    }
}

/** Test-only no-op used in unit tests that don't exercise the reconcile path. */
object NoopItemProgressPuller : ItemProgressPuller {
    override suspend fun pullItem(sourceId: String, itemId: String) = Unit
}
