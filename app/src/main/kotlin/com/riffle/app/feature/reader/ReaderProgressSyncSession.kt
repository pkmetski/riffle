package com.riffle.app.feature.reader

import com.riffle.core.domain.ProgressSyncController
import com.riffle.core.domain.ReadingSessionRepository
import com.riffle.core.domain.ServerProgress
import com.riffle.core.domain.SessionPayload
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharedFlow

/**
 * The "sync out of the box" seam for a single open book (#528, follow-up refactor to the
 * capability generalisation).
 *
 * Every reader that opens a book from a source implementing `ProgressPeerCapability` needs the
 * same wiring: kick a sync cycle on open, listen for `ServerWins`, and re-kick after every local
 * save. Before this class, PDF, EPUB, and CBZ each constructed their own [ProgressSyncController]
 * inline — three copies of the same wire, with `itemId` re-passed to every `sync()` call, and no
 * compile-time enforcement that a new reader remembers the pattern. That's why the CBZ reader
 * shipped without sync at all and a Komga comic in progress on the server never opened at the
 * server-side page on device.
 *
 * This is a deliberately thin wrapper: it binds `itemId` at construction (so `sync(payload)` is a
 * one-arg call), owns the [ProgressSyncController], and re-exports its stream. Adding new
 * per-book sync behavior later (retries, telemetry, batching) lands here — one place, all readers.
 */
class ReaderProgressSyncSession(
    private val itemId: String,
    readingSessionRepository: ReadingSessionRepository,
    scope: CoroutineScope,
    onSyncError: () -> Unit = {},
) {
    private val controller: ProgressSyncController =
        ProgressSyncController(readingSessionRepository, scope, onSyncError)

    /**
     * Fires whenever a sync cycle resolves to `ServerWins`. Reader UIs collect this and translate
     * the [ServerProgress.ebookLocation] into a reader-native jump (page index, Readium
     * [org.readium.r2.shared.publication.Locator], etc.). Emissions are conflated
     * (`extraBufferCapacity=1`) so a burst collapses to the latest server position.
     */
    val serverPositionEvents: SharedFlow<ServerProgress> = controller.serverPositionEvents

    /**
     * Kick a sync cycle for the local [payload]. Idempotent — safe to call on open, on every
     * save, and on network-restored. The bound [itemId] is added by this class so the reader
     * never has to thread it through.
     */
    fun sync(payload: SessionPayload) {
        controller.sync(itemId, payload)
    }
}
