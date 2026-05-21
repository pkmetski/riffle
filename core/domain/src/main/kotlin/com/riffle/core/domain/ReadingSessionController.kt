package com.riffle.core.domain

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

class ReadingSessionController(
    private val repository: ReadingSessionRepository,
    private val scope: CoroutineScope,
    private val onSyncError: () -> Unit = {},
) {
    fun sync(itemId: String, payload: SessionPayload) {
        scope.launch {
            when (val r = repository.syncProgress(itemId, payload)) {
                is SyncSessionResult.Success -> Unit
                is SyncSessionResult.NetworkError -> onSyncError()
            }
        }
    }
}
