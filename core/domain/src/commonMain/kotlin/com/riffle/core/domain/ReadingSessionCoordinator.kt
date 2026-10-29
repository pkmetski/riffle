package com.riffle.core.domain

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class ReadingSessionCoordinator(
    private val scope: CoroutineScope,
    private val syncIntervalMs: Long = SYNC_INTERVAL_MS,
    private val enabled: () -> Boolean = { true },
) {

    private var syncJob: Job? = null

    fun onResumed(onTick: suspend () -> Unit) {
        syncJob?.cancel()
        syncJob = scope.launch {
            while (true) {
                delay(syncIntervalMs)
                if (enabled()) onTick()
            }
        }
    }

    fun onClosed() {
        syncJob?.cancel()
        syncJob = null
    }

    companion object {
        const val SYNC_INTERVAL_MS = 30_000L
    }
}
