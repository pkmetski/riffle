package com.riffle.core.sync

import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Drives the dirty sweeps from the only three triggers a host without background execution has:
 * **app start**, **app foreground** and the **validated offline→online edge**.
 *
 * Android gets durability from WorkManager: `ProgressSyncScheduler.sweepNow` +
 * `ensurePeriodic` survive process death and fire while the app is not running, with
 * [kickSweepsOnReconnect] layered on top for mid-session reconnects. iOS has none of that —
 * `Info.plist` declares only `UIBackgroundModes: audio` and there is no `BGTaskScheduler`
 * registration, so before #1071 §14 a progress push that failed offline was simply lost: nothing
 * retried it, and the row stayed dirty until the user happened to reopen that exact book while
 * online. This driver is iOS's honest replacement. It cannot run while the app is suspended, but
 * it does run at every moment the user could observe stale state.
 *
 * ### Why the throttle
 * A cold launch fires app-start and `UIApplicationDidBecomeActive` within milliseconds of each
 * other, and a reconnect can land on top. Every pass walks the whole dirty ledger and issues
 * network writes, so passes are coalesced inside [minIntervalMs] and serialised by a mutex —
 * two concurrent sweeps over the same rows would race the `ifLocalUpdatedAt` guards.
 *
 * ### Why the reconnect edge is exempt
 * The reconnect edge is the one trigger that carries new information ("the writes that just
 * failed can now succeed"), so [sweepProgressNow] / [sweepAnnotationsNow] bypass the throttle.
 * They still take the mutex, so they never overlap a foreground pass.
 *
 * Every sweep is wrapped in `runCatching`: a failing sweep must never take the app down, and must
 * never stop a later trigger from retrying.
 */
class ForegroundSyncDriver(
    private val runProgressSweep: suspend () -> Unit,
    private val runAnnotationSweep: suspend () -> Unit = {},
    private val nowMs: () -> Long,
    private val minIntervalMs: Long = DEFAULT_MIN_INTERVAL_MS,
) {
    private val mutex = Mutex()
    private var lastPassAtMs: Long? = null

    /**
     * App start or app foreground. Returns `true` when a pass actually ran, `false` when it was
     * coalesced into a pass that started less than [minIntervalMs] ago.
     */
    suspend fun onAppActive(): Boolean = mutex.withLock {
        val last = lastPassAtMs
        if (last != null && nowMs() - last < minIntervalMs) return@withLock false
        lastPassAtMs = nowMs()
        runCatching { runProgressSweep() }
        runCatching { runAnnotationSweep() }
        true
    }

    /** Progress half of a reconnect pass — never throttled. */
    suspend fun sweepProgressNow() {
        mutex.withLock {
            lastPassAtMs = nowMs()
            runCatching { runProgressSweep() }
        }
    }

    /** Annotation half of a reconnect pass — never throttled. */
    suspend fun sweepAnnotationsNow() {
        mutex.withLock {
            lastPassAtMs = nowMs()
            runCatching { runAnnotationSweep() }
        }
    }

    /**
     * Collects all three triggers for as long as the caller's scope lives. Suspends forever;
     * launch it on an application-lifetime scope.
     */
    suspend fun drive(appBecameActive: Flow<Unit>, isOnline: Flow<Boolean>): Unit = coroutineScope {
        launch { onAppActive() }
        launch { appBecameActive.collect { onAppActive() } }
        launch {
            kickSweepsOnReconnect(
                isOnline = isOnline,
                runProgressSweep = { sweepProgressNow() },
                runAnnotationSweep = { sweepAnnotationsNow() },
            )
        }
    }

    companion object {
        /**
         * Long enough to swallow the app-start / foreground double-fire and the user flicking
         * between apps, short enough that a deliberate background-and-return still syncs.
         */
        const val DEFAULT_MIN_INTERVAL_MS: Long = 30_000L

        /** Koin qualifier for the host's `Flow<Unit>` of "the app became active" events. */
        const val APP_BECAME_ACTIVE: String = "appBecameActive"
    }
}
