package com.riffle.app.sync

import kotlinx.coroutines.flow.StateFlow

/**
 * Runs the progress + annotation sweeps in-process on every offline→online transition of the
 * validated connectivity flow.
 *
 * Why in-process rather than [AnnotationSyncScheduler.sweepNow] / [ProgressSyncScheduler.sweepNow]:
 * those enqueue WorkManager jobs gated on OS-level `NetworkType.CONNECTED`, the same raw signal
 * PR #402's `ValidatedNetworkTracker` was built to work around. On Huawei / Android 13 / captive
 * portal devices the validated observer can report "online" while the OS-level constraint still
 * says "no network" — leaving the queued sweep stalled until the 1-hour periodic tick, so the
 * "will retry when connectivity returns" banner reads as a broken promise. The validated edge is
 * authoritative — honour it by running the sweep directly. WorkManager remains the cold-start
 * and process-death durability backstop.
 *
 * The first StateFlow emission is the current value; only the false→true edge triggers a sweep,
 * so starting online is a no-op.
 */
internal suspend fun kickSweepsOnReconnect(
    isOnline: StateFlow<Boolean>,
    runProgressSweep: suspend () -> Unit,
    runAnnotationSweep: suspend () -> Unit,
) {
    var wasOnline = isOnline.value
    isOnline.collect { online ->
        if (online && !wasOnline) {
            runProgressSweep()
            runAnnotationSweep()
        }
        wasOnline = online
    }
}
