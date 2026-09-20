package com.riffle.shared

import com.riffle.core.domain.ContentCacheCleaner
import com.riffle.core.sync.ForegroundSyncDriver
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

/**
 * The startup work `RiffleAppRoot` kicks off, hoisted out of the composable so it runs on [io]
 * rather than on the composition's dispatcher.
 *
 * `LaunchedEffect` inherits the composition context, which on iOS is the **main** dispatcher.
 * [ForegroundSyncDriver] therefore ran [com.riffle.core.sync.ProgressSweep] on the UI thread:
 * that sweep walks the dirty ledger (database reads) and issues network writes, and it performs
 * **no dispatcher switch of its own** — on Android it never needed one, because its only caller
 * is a WorkManager worker that is already off the main thread. Driving it from a composable is
 * what put it there, and #1071 §14 is what started driving it.
 *
 * (The cache sweep is already safe on its own — `ContentCacheCleaner.cleanExpired` wraps its work
 * in `withContext(dispatchers.io)`. It is included here so both startup jobs share one
 * off-the-UI-thread entry point rather than each relying on its callee to be careful.)
 *
 * [startDelayMs] keeps both off the launch critical path. Moving them off the UI thread stopped
 * them blocking first paint, but on a device with few cores they still competed with it for CPU
 * and I/O — the iOS harness's cold-launch test measured about eight seconds of it. Neither job is
 * urgent: the cache has been stale since the last run and the dirty ledger since the last
 * failure, so a short wait costs nothing and hands the whole launch to the UI.
 *
 * Failures are swallowed on purpose. Neither job is worth taking the app down for, and both
 * retry: the cache sweep on the next launch, the progress sweep on the next foreground.
 */
internal suspend fun runStartupWork(
    io: CoroutineDispatcher,
    contentCacheCleaner: ContentCacheCleaner,
    syncDriver: ForegroundSyncDriver,
    appBecameActive: Flow<Unit>,
    isOnline: Flow<Boolean>,
    startDelayMs: Long = STARTUP_WORK_DELAY_MS,
) {
    withContext(io) {
        delay(startDelayMs)
        runCatching { contentCacheCleaner.cleanExpired() }
        // Suspends for the lifetime of the composition, collecting foreground + reconnect edges.
        // The driver swallows every sweep failure internally, so nothing escapes here either.
        syncDriver.drive(appBecameActive = appBecameActive, isOnline = isOnline)
    }
}

/** Long enough for the first frame to win the race, short enough to be invisible. */
internal const val STARTUP_WORK_DELAY_MS: Long = 3_000
