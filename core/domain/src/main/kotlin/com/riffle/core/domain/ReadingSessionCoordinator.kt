package com.riffle.core.domain

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Lifts the ABS Reading Session lifecycle out of the reader ViewModels (issue #339) so EPUB, PDF,
 * and any future reader format share one implementation of:
 *
 *  - the periodic position-sync heartbeat (`delay(syncIntervalMs) → onTick()`),
 *  - the per-resume reading-speed session (start progression + start instant → on close, feed
 *    [ReadingSpeedTracker] and persist via [ReadingSpeedStore]).
 *
 * The actual per-tick sync action is supplied by the caller as [onTick] in [onResumed] so the
 * coordinator stays oblivious to EPUB's `readerSync` / inbound-jump branch and PDF's plain
 * `progressSyncController.sync(...)` path. ViewModels keep the small amount of format-specific
 * code, but the timer and the speed-tracker session live here.
 *
 * Scope: [scope] is intended to be the caller's `viewModelScope` — the heartbeat dies with the
 * screen by design (no value in heartbeating an unmounted reader). The terminal speed-tracker
 * write fires from [onClosed] before [scope] is cancelled.
 */
class ReadingSessionCoordinator(
    private val clock: Clock,
    private val readingSpeedStore: ReadingSpeedStore,
    private val scope: CoroutineScope,
    private val syncIntervalMs: Long = SYNC_INTERVAL_MS,
    // Whether reading-session lifecycle actions should fire at all. Evaluated per call so the
    // Catalog capability check can be resolved after construction (readers set this once the
    // active Source's Catalog is known). False when the active Source has no
    // ReadingSessionsCapability (issue #439 / ADR 0041) — LocalFiles, for example. Both
    // [onResumed] and [onClosed] become no-ops so the heartbeat never starts and the
    // speed-tracker session is never flushed. Defaults to `always-on` to keep pre-#439 callers
    // (and every ABS Source) unchanged.
    private val enabled: () -> Boolean = { true },
) {

    private var syncJob: Job? = null
    private var sessionStartProgression: Float? = null
    private var sessionStartMs: Long = 0L

    /**
     * Start (or restart) a reading session.
     *
     * @param initialTotalProgression the canonical book-wide progression at the moment the reader
     *   becomes active; captured as the baseline so [onClosed] can compute the session delta. Null
     *   means "we don't know the position yet" — the speed-tracker session is suppressed until the
     *   next [onResumed] call provides a real value.
     * @param onTick the per-heartbeat action — typically a position-sync call. Invoked on [scope]
     *   every [syncIntervalMs] ms after a fresh delay (so the first tick fires one interval in,
     *   matching the pre-refactor behaviour); callers that want an immediate sync should call it
     *   themselves alongside [onResumed].
     */
    fun onResumed(initialTotalProgression: Float?, onTick: suspend () -> Unit) {
        if (!enabled()) return
        sessionStartProgression = initialTotalProgression
        sessionStartMs = clock.nowMs()
        syncJob?.cancel()
        syncJob = scope.launch {
            while (true) {
                delay(syncIntervalMs)
                onTick()
            }
        }
    }

    /**
     * End the session: cancel the heartbeat and flush the speed-tracker. Safe to call when no
     * session is active (no-op).
     *
     * @param currentTotalProgression the canonical book-wide progression at close — paired with
     *   the [onResumed] baseline to compute [ReadingSpeedTracker.recordSession]'s `progressDelta`.
     * @param totalPositions the book's total weighted positions (sum of chapter weights); same
     *   value the rail uses. A zero total skips the speed write (we'd divide by zero).
     */
    fun onClosed(currentTotalProgression: Float?, totalPositions: Float) {
        if (!enabled()) return
        syncJob?.cancel()
        syncJob = null
        flushSpeedSession(currentTotalProgression, totalPositions)
    }

    private fun flushSpeedSession(currentTotalProgression: Float?, totalPositions: Float) {
        val startProg = sessionStartProgression ?: return
        sessionStartProgression = null
        val timeDeltaSec = (clock.nowMs() - sessionStartMs) / 1000.0
        val totalProg = currentTotalProgression ?: return
        if (totalPositions == 0f) return
        val progressDelta = totalProg - startProg
        scope.launch {
            val prior = readingSpeedStore.speedSecPerPosition.first()
            val updated = ReadingSpeedTracker.recordSession(progressDelta, timeDeltaSec, totalPositions, prior)
            if (updated != null) readingSpeedStore.updateSpeed(updated)
        }
    }

    companion object {
        const val SYNC_INTERVAL_MS = 30_000L
    }
}
