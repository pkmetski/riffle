package com.riffle.app.sync

import com.riffle.core.domain.collectReconnects
import kotlinx.coroutines.flow.Flow

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
 * The edge semantics live in [collectReconnects], shared with the library auto-refresh listener.
 */
internal suspend fun kickSweepsOnReconnect(
    isOnline: Flow<Boolean>,
    runProgressSweep: suspend () -> Unit,
    runAnnotationSweep: suspend () -> Unit,
) {
    isOnline.collectReconnects {
        runProgressSweep()
        runAnnotationSweep()
    }
}
