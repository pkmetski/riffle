package com.riffle.core.data

import com.riffle.core.database.AnnotationDao
import com.riffle.core.database.AnnotationEntity
import com.riffle.core.domain.AnnotationFileHeader
import com.riffle.core.domain.AnnotationSweepEnqueuer
import com.riffle.core.domain.AnnotationSyncTarget
import com.riffle.core.domain.DeviceIdStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Owns the per-book debounced push and the close-flush handshake. Serializes reads-then-writes
 * against the durable [AnnotationSweep] via [AnnotationLockPort] so a live push and a background
 * sweep cannot interleave on the same device file (#321, ADR 0036).
 */
internal class AnnotationPushCoordinator(
    private val targetProvider: () -> AnnotationSyncTarget?,
    private val annotationDao: AnnotationDao,
    private val deviceIdStore: DeviceIdStore,
    private val statusStore: AnnotationSyncStatusStore,
    private val sweepEnqueuer: AnnotationSweepEnqueuer,
    private val sentinelWriter: DeviceMetaSentinelWriter,
    private val locks: AnnotationLockPort,
    private val bookTitleProvider: suspend (serverId: String, itemId: String) -> String?,
    private val scope: CoroutineScope,
    private val clock: () -> Long,
    private val tombstoneTtlMs: Long,
    private val debounceDurationMs: Long,
) {
    private val debouncingJobs = mutableMapOf<Pair<String, String>, Job>()

    /**
     * Schedule a debounced push. Per-book timer; restarts on each call. No-op when the target is
     * currently unset (sync disabled).
     */
    fun scheduleDebounce(serverId: String, namespace: String, itemId: String) {
        if (targetProvider() == null) return

        val key = serverId to itemId
        debouncingJobs[key]?.cancel()
        debouncingJobs[key] = scope.launch {
            delay(debounceDurationMs)
            pushPending(serverId, namespace, itemId)
        }
    }

    /** Cancel any pending debounce and flush this device's annotations to the target. */
    suspend fun syncOnClose(serverId: String, namespace: String, itemId: String) {
        if (targetProvider() == null) return

        val key = serverId to itemId
        debouncingJobs[key]?.cancel()
        debouncingJobs.remove(key)
        pushPending(serverId, namespace, itemId)
    }

    private suspend fun pushPending(serverId: String, namespace: String, itemId: String) {
        val target = targetProvider() ?: return

        val pushed = try {
            // Hold the per-book annotation lock across the read-then-write so the durable
            // [AnnotationSweep] cannot interleave on the same device file (#321, ADR 0036).
            locks.withAnnotationLock(serverId, itemId) {
                pushPendingLocked(target, serverId, namespace, itemId)
            }
        } catch (e: Exception) {
            statusStore.report(e.toFailedCycleOutcome(clock()))
            sweepEnqueuer.enqueue()
            return
        }
        // Sentinel writes a different file than the annotations payload, so it doesn't need the
        // per-book lock — keep it out to avoid serializing an extra remote round-trip against the
        // sweep's reconcile path.
        if (pushed) sentinelWriter.writeQuietly(target, namespace, serverId)
    }

    private suspend fun pushPendingLocked(
        target: AnnotationSyncTarget,
        serverId: String,
        namespace: String,
        itemId: String,
    ): Boolean {
        // Include tombstones so deletes propagate; an annotation with deleted=1 still carries
        // its updatedAt/lastModifiedByDeviceId, and the LWW merge on the receiver will trust
        // the newer tombstone over any older live copy of the same id.
        val now = clock()
        val cutoff = now - tombstoneTtlMs
        val beforeSweep = annotationDao.getAllForItemIncludingDeleted(serverId, itemId)
        // ADR 0038 rule 1+2 — preview the sweep in memory. If it would leave us empty, DELETE the
        // WebDAV file BEFORE mutating Room. A failed DELETE aborts before Room changes so the
        // next attempt still sees `beforeSweep.isNotEmpty()` and retries.
        val remainingAfterSweep = beforeSweep.filter { !isAgedSyncedTomb(it, cutoff) }

        if (remainingAfterSweep.isEmpty()) {
            if (beforeSweep.isNotEmpty()) {
                // Rule 2 empty-file DELETE. Do it first, then commit the sweep to Room.
                val deviceId = deviceIdStore.getOrCreate()
                target.delete(namespace, itemId, "annotations-$deviceId.jsonld")
                annotationDao.purgeAgedTombstones(serverId, itemId, cutoff)
                statusStore.report(CycleOutcome.Success(now))
                return true
            }
            // Preserve pre-ADR-0038 behaviour: don't touch WebDAV on a transient/cleared-data
            // Room-already-empty state.
            return false
        }

        // Non-empty case: commit the sweep, then push the survivors.
        annotationDao.purgeAgedTombstones(serverId, itemId, cutoff)
        val localEntities = remainingAfterSweep

        val deviceId = deviceIdStore.getOrCreate()
        val jsonStrings = localEntities.map { entity ->
            AnnotationW3CCodec.annotationEntityToW3C(entity)
        }
        // Embed the file header at position 0 of the array. Old readers already drop entries
        // with no `id`, so the header is invisible to merge. Device-scoped metadata
        // (label, lastSyncedAt, username) lives in the per-device sentinel — see
        // [AnnotationDeviceMeta] — so this header carries only the book-scoped bookTitle.
        val header = AnnotationFileHeader(
            deviceId = deviceId,
            bookTitle = bookTitleProvider(serverId, itemId),
        )
        val jsonArray = AnnotationFileHeaderCodec.buildFileBody(header, jsonStrings)
        val filename = "annotations-$deviceId.jsonld"
        target.write(namespace, itemId, filename, jsonArray)
        annotationDao.markSynced(localEntities.map { it.id }, now)
        statusStore.report(CycleOutcome.Success(now))
        return true
    }

    private fun isAgedSyncedTomb(row: AnnotationEntity, cutoff: Long): Boolean =
        row.deleted && row.updatedAt < cutoff && row.updatedAt <= row.lastSyncedAt
}
