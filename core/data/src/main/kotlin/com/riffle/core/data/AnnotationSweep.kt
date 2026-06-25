package com.riffle.core.data

import com.riffle.core.database.AnnotationDao
import com.riffle.core.domain.AnnotationSyncTarget
import com.riffle.core.domain.DeviceIdStore
import com.riffle.core.domain.ServerRepository

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
    private val serverRepository: ServerRepository,
    private val statusStore: AnnotationSyncStatusStore,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    suspend fun run() {
        val target = targetProvider() ?: return

        try {
            val deviceId = deviceIdStore.getOrCreate()
            for ((serverId, itemId) in annotationDao.dirtyServerItems()) {
                val namespace = serverRepository.ensureAbsUserId(serverId) ?: continue
                pushBook(target, serverId, namespace, itemId, deviceId)
            }
            statusStore.report(CycleOutcome.Success(clock()))
        } catch (e: Exception) {
            statusStore.report(e.toFailedCycleOutcome(clock()))
        }
    }

    private suspend fun pushBook(
        target: AnnotationSyncTarget,
        serverId: String,
        namespace: String,
        itemId: String,
        deviceId: String,
    ) {
        val rows = annotationDao.getAllForItemIncludingDeleted(serverId, itemId)
        if (rows.isEmpty()) return
        val jsonArray = "[\n" + rows.joinToString(",\n") { AnnotationW3CCodec.annotationEntityToW3C(it) } + "\n]"
        target.write(namespace, itemId, "annotations-$deviceId.jsonld", jsonArray)
        annotationDao.markSynced(rows.map { it.id }, clock())
    }
}
