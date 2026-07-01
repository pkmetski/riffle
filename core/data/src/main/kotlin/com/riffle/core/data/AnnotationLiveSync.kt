package com.riffle.core.data

import com.riffle.core.domain.AnnotationSyncTarget
import com.riffle.core.domain.DeviceIdStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Per-book pull loop: polls the target every [liveSyncIntervalMs] so peer-device changes appear
 * while the reader stays open. Delegates the merge branch to [AnnotationMergeOrchestrator] when a
 * peer file is present; when this device is the only writer, reports Success and stamps the
 * sentinel without a redundant re-read of our own file.
 */
internal class AnnotationLiveSync(
    private val targetProvider: () -> AnnotationSyncTarget?,
    private val orchestrator: AnnotationMergeOrchestrator,
    private val deviceIdStore: DeviceIdStore,
    private val statusStore: AnnotationSyncStatusStore,
    private val sentinelWriter: DeviceMetaSentinelWriter,
    private val scope: CoroutineScope,
    private val clock: () -> Long,
    private val liveSyncIntervalMs: Long,
) {
    /**
     * Start the loop. First tick fires **after** [liveSyncIntervalMs] — the caller is expected to
     * have already invoked the open-book sync once, so an immediate tick here would race that.
     * Per-tick failures are caught and reported; the loop itself does not die on transients.
     */
    fun start(serverId: String, namespace: String, itemId: String): Job = scope.launch {
        // Resolve our own filename once; deviceId is stable for the install lifetime. A DataStore
        // failure here would otherwise kill the launch silently before the first delay().
        val myFilename = try {
            "annotations-${deviceIdStore.getOrCreate()}.jsonld"
        } catch (e: Exception) {
            statusStore.report(e.toFailedCycleOutcome(clock()))
            return@launch
        }
        while (true) {
            delay(liveSyncIntervalMs)
            val target = targetProvider() ?: continue
            val filenames = try {
                target.list(namespace, itemId)
            } catch (e: Exception) {
                statusStore.report(e.toFailedCycleOutcome(clock()))
                continue
            }
            val hasPeer = filenames.any { it != myFilename }
            if (hasPeer) {
                // Pass the captured target so the merge sees the same instance we just listed —
                // avoids a holder-swap race where the second resolve returns null and the tick
                // silently no-ops with the listed filenames discarded.
                orchestrator.mergeFromListing(target, serverId, namespace, itemId, filenames)
            } else {
                // Solo namespace this tick — PROPFIND was the only work needed. Report Success so
                // the status badge can recover from a prior Failed state without forcing a no-op
                // merge that would only re-read our own file.
                statusStore.report(CycleOutcome.Success(clock()))
                sentinelWriter.writeQuietly(target, namespace, serverId)
            }
        }
    }
}
