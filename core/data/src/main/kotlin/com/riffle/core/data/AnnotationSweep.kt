package com.riffle.core.data

import com.riffle.core.database.AnnotationDao
import com.riffle.core.domain.AnnotationSyncTarget
import com.riffle.core.domain.DeviceIdStore
import com.riffle.core.domain.DeviceLabelResolver
import com.riffle.core.domain.AnnotationFileHeader
import com.riffle.core.domain.ServerRepository
import java.time.Instant

/**
 * Push-only sweep over annotation rows whose `updatedAt > lastSyncedAt`. Companion to the live
 * [AnnotationSyncController] — the controller pushes opportunistically on reader open/close/debounce;
 * the sweep is the durability backstop (ADR 0036).
 *
 * One book at a time, no parallelism. The first failure aborts the cycle: WebDAV failures are
 * almost always connection-scoped (auth/network/TLS), so attempting subsequent books would issue
 * identical failing requests. Remaining dirty rows survive untouched for the next cycle.
 *
 * Unconfigured target ⇒ silent no-op; status store is **not** touched (treating "no sync target
 * set up" as "Success" or "Failure" would both be misleading).
 */
class AnnotationSweep(
    private val targetProvider: () -> AnnotationSyncTarget?,
    private val annotationDao: AnnotationDao,
    private val deviceIdStore: DeviceIdStore,
    private val deviceLabelResolver: DeviceLabelResolver,
    private val serverRepository: ServerRepository,
    private val statusStore: AnnotationSyncStatusStore,
    /**
     * Resolves the local catalog's book title for a (serverId, itemId). Embedded in the header
     * so Maintenance can surface "Project Hail Mary" instead of an opaque itemId. Returns null
     * when the catalog hasn't cached the title yet — header renderer falls back to the id.
     */
    private val bookTitleProvider: suspend (serverId: String, itemId: String) -> String? = { _, _ -> null },
    private val nowIso: () -> String = { Instant.now().toString() },
    private val clock: () -> Long = System::currentTimeMillis,
    /**
     * Enumerates the dirty (serverId, itemId) pairs to push (#321). Defaults to a thin wrapper
     * over [annotationDao]; production wiring (DI) supplies the [RoomDirtyAnnotationLedger]
     * binding explicitly.
     */
    private val dirtyLedger: DirtyAnnotationLedger =
        DirtyAnnotationLedger { annotationDao.dirtyServerItems() },
    /**
     * Per-book mutex shared with [AnnotationSyncController] (#321). Held across the per-book
     * read-then-write so the sweep and a live push cannot interleave on the same device file.
     * Defaults to a fresh instance for tests; production wiring (DI) supplies the singleton.
     */
    private val locks: ReconcileLocks = ReconcileLocks(),
    /**
     * Shared sentinel-writer (#321). Defaults to one built from this sweep's own deps so existing
     * tests don't have to construct one; production wiring injects the singleton.
     */
    private val sentinelWriter: DeviceMetaSentinelWriter = DeviceMetaSentinelWriter(
        deviceIdStore,
        deviceLabelResolver,
        { sid -> serverRepository.getById(sid)?.username },
        nowIso,
    ),
) {
    /**
     * Runs one push cycle. Returns the [CycleOutcome] reported to the status store, or `null` when
     * the sync target is unconfigured (silent no-op — see class kdoc). The worker maps the outcome
     * to a [androidx.work.ListenableWorker.Result] so transient failures get WorkManager's
     * exponential-backoff retry, which is also what re-fires the work the moment connectivity
     * returns (CONNECTED constraint on the retried JobInfo).
     */
    suspend fun run(): CycleOutcome? {
        val target = targetProvider() ?: return null

        // Sentinels are written once per unique namespace touched this cycle. Skip pull-only
        // sweeps with nothing dirty — the live controller refreshes sentinels on its own paths
        // and writing here would require iterating every known namespace.
        val sentinelTargets = mutableSetOf<Pair<String, String>>()
        val outcome: CycleOutcome = try {
            val deviceId = deviceIdStore.getOrCreate()
            for ((serverId, itemId) in dirtyLedger.dirtyServerItems()) {
                val namespace = serverRepository.ensureAbsUserId(serverId) ?: continue
                val bookTitle = bookTitleProvider(serverId, itemId)
                // Hold the per-book lock across the read-then-write so the live
                // [AnnotationSyncController] cannot interleave on the same device file
                // (#321, ADR 0036).
                locks.withAnnotationLock(serverId, itemId) {
                    pushBook(target, serverId, namespace, itemId, deviceId, bookTitle)
                }
                sentinelTargets += serverId to namespace
            }
            for ((serverId, namespace) in sentinelTargets) {
                sentinelWriter.writeQuietly(target, namespace, serverId)
            }
            CycleOutcome.Success(clock())
        } catch (e: Exception) {
            e.toFailedCycleOutcome(clock())
        }
        statusStore.report(outcome)
        return outcome
    }

    private suspend fun pushBook(
        target: AnnotationSyncTarget,
        serverId: String,
        namespace: String,
        itemId: String,
        deviceId: String,
        bookTitle: String?,
    ) {
        val rows = annotationDao.getAllForItemIncludingDeleted(serverId, itemId)
        if (rows.isEmpty()) return
        val jsonStrings = rows.map { AnnotationW3CCodec.annotationEntityToW3C(it) }
        // Mirror the header written by AnnotationSyncController.pushPending so files from the
        // sweep and from the live controller are format-identical. Device-scoped metadata
        // lives in the per-device sentinel — see [AnnotationDeviceMeta].
        val header = AnnotationFileHeader(deviceId = deviceId, bookTitle = bookTitle)
        val jsonArray = AnnotationFileHeaderCodec.buildFileBody(header, jsonStrings)
        target.write(namespace, itemId, "annotations-$deviceId.jsonld", jsonArray)
        annotationDao.markSynced(rows.map { it.id }, clock())
    }
}
