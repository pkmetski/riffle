package com.riffle.core.domain

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch

/**
 * The "sync out of the box" seam every reader ViewModel constructs (#528).
 *
 * Binds [itemId] at construction so `sync(payload)` is a one-arg call — the reader can't
 * mis-thread the id, and any per-book behavior added later (retries, telemetry, batching) lives
 * on this one type. Same shape for PDF, EPUB, and CBZ.
 */
class ProgressSyncController(
    private val itemId: String,
    private val repository: ReadingSessionRepository,
    private val scope: CoroutineScope,
    private val onSyncError: () -> Unit = {},
) {
    private val _serverPositionEvents = MutableSharedFlow<ServerProgress>(extraBufferCapacity = 1)
    val serverPositionEvents: SharedFlow<ServerProgress> = _serverPositionEvents.asSharedFlow()

    fun sync(payload: SessionPayload) {
        scope.launch {
            when (val r = repository.runSyncCycle(itemId, payload)) {
                is ProgressSyncCycleResult.ServerWins -> _serverPositionEvents.tryEmit(r.serverProgress)
                is ProgressSyncCycleResult.LocalWins -> Unit
                is ProgressSyncCycleResult.InSync -> Unit
                is ProgressSyncCycleResult.Offline -> Unit
            }
        }
    }
}
