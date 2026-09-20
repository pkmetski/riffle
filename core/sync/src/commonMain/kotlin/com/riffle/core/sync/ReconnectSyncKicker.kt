package com.riffle.core.sync

import com.riffle.core.domain.collectReconnects
import kotlinx.coroutines.flow.Flow

/**
 * Runs the progress + annotation sweeps in-process on every offline→online transition of the
 * validated connectivity flow.
 *
 * Why in-process rather than `AnnotationSyncScheduler.sweepNow` / `ProgressSyncScheduler.sweepNow`:
 * those enqueue WorkManager jobs gated on OS-level `NetworkType.CONNECTED`, the same raw signal
 * PR #402's `ValidatedNetworkTracker` was built to work around. On Huawei / Android 13 / captive
 * portal devices the validated observer can report "online" while the OS-level constraint still
 * says "no network" — leaving the queued sweep stalled until the 1-hour periodic tick, so the
 * "will retry when connectivity returns" banner reads as a broken promise. The validated edge is
 * authoritative — honour it by running the sweep directly. WorkManager remains the cold-start
 * and process-death durability backstop.
 *
 * This lives in `core:sync` rather than `:app` (where it was until #1071 §14) because iOS needs
 * the identical edge behaviour: it has **no** WorkManager/`BGTaskScheduler` backstop at all, so
 * the reconnect kick plus the app-start / foreground sweeps are the *only* thing that ever retries
 * a push that failed offline. `runAnnotationSweep` is a lambda precisely so iOS can pass a no-op
 * while `AnnotationSync` remains Android-only (#1072).
 *
 * The edge semantics live in [collectReconnects], shared with the library auto-refresh listener.
 */
suspend fun kickSweepsOnReconnect(
    isOnline: Flow<Boolean>,
    runProgressSweep: suspend () -> Unit,
    runAnnotationSweep: suspend () -> Unit,
) {
    isOnline.collectReconnects {
        runProgressSweep()
        runAnnotationSweep()
    }
}
