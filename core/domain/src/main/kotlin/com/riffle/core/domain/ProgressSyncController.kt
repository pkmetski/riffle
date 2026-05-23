package com.riffle.core.domain

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch

class ProgressSyncController(
    private val repository: ReadingSessionRepository,
    private val scope: CoroutineScope,
    private val onSyncError: () -> Unit = {},
) {
    private val _serverPositionEvents = MutableSharedFlow<ServerProgress>(extraBufferCapacity = 1)
    val serverPositionEvents: SharedFlow<ServerProgress> = _serverPositionEvents.asSharedFlow()

    fun sync(itemId: String, payload: SessionPayload) {
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
