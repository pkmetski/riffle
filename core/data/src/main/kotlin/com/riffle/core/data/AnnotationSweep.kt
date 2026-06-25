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
) {
    suspend fun run() {
        val target = targetProvider() ?: return

        try {
            val deviceId = deviceIdStore.getOrCreate()
            for ((serverId, itemId) in annotationDao.dirtyServerItems()) {
                val namespace = serverRepository.ensureAbsUserId(serverId) ?: continue
                val username = serverRepository.getById(serverId)?.username
                val bookTitle = bookTitleProvider(serverId, itemId)
                pushBook(target, serverId, namespace, itemId, deviceId, username, bookTitle)
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
        username: String?,
        bookTitle: String?,
    ) {
        val rows = annotationDao.getAllForItemIncludingDeleted(serverId, itemId)
        if (rows.isEmpty()) return
        val jsonStrings = rows.map { AnnotationW3CCodec.annotationEntityToW3C(it) }
        // Mirror the header written by AnnotationSyncController.pushPending so files from the
        // sweep and from the live controller are format-identical. Without this, the sweep would
        // overwrite the header on every alternate push.
        val header = AnnotationFileHeader(
            deviceId = deviceId,
            label = deviceLabelResolver.resolveLabel(deviceId),
            lastSeenAt = nowIso(),
            username = username,
            bookTitle = bookTitle,
        )
        val jsonArray = AnnotationFileHeaderCodec.buildFileBody(header, jsonStrings)
        target.write(namespace, itemId, "annotations-$deviceId.jsonld", jsonArray)
        annotationDao.markSynced(rows.map { it.id }, clock())
    }
}
