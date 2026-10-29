package com.riffle.core.domain

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Periodic position-sync heartbeat shared by EPUB, PDF, and CBZ readers (issue #339).
 *
 * Fires [onTick] every [syncIntervalMs] while the reader is active. Purely concerned with
 * the timer; reading-speed tracking is a separate responsibility handled by [ReadingSpeedSession].
 *
 * The [enabled] flag lets the caller gate ticks on a lazily-resolved catalog capability (ADR 0048
 * / issue #439): evaluated per-tick so a catalog probe that races [onResumed] does not silently
 * kill the entire session.
 */
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
