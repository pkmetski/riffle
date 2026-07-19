package com.riffle.core.data

import com.riffle.core.models.AnnotationDeviceMeta
import com.riffle.core.domain.AnnotationSyncTarget
import com.riffle.core.domain.DeviceFileSummary
import com.riffle.core.domain.NamespaceSummary
import java.time.Instant

/**
 * Manual housekeeping for the per-device-file annotation-sync model (issue #78).
 *
 * Single user-initiated action: [forgetDevice] — DELETE every annotation file owned by a single
 * `deviceId` under one namespace. Safe under the all-mirrors-everything push model because each
 * peer's file already carries the same records; the only risk is data loss from devices that
 * went offline before any other device synced their last edits.
 *
 * A tombstone-compaction action was prototyped and removed — see ADR 0025 for the rationale.
 * Operates against the controller's active target (gracefully no-ops when sync is disabled).
 */
class AnnotationSyncMaintenance(
    private val targetProvider: () -> AnnotationSyncTarget?,
    private val nowIso: () -> String = { Instant.now().toString() },
) {
    /** Outcome of [publishHeader] — lets the UI report partial failure to the user. */
    data class PublishMetadataResult(
        val rewrittenFiles: Int,
        val failures: Int,
    )

    /**
     * Refresh this device's metadata sentinel under [namespace]. Used after a rename so peer
     * devices see the new label without waiting for the next sync cycle. A rename does not
     * masquerade as a fresh sync — the sentinel's `lastSyncedAt` is preserved when present so
     * the visible "Last synced" timestamp stays a real signal of when this device last actually
     * synced. Returns counts so the caller can surface success/failure.
     */
    suspend fun publishHeader(
        namespace: String,
        deviceId: String,
        label: String,
        username: String?,
    ): PublishMetadataResult {
        val target = targetProvider() ?: return PublishMetadataResult(0, 0)
        val existing = try {
            target.readDeviceMeta(namespace, deviceId)?.let { AnnotationDeviceMetaCodec.decode(it) }
        } catch (_: Exception) {
            null
        }
        val meta = AnnotationDeviceMeta(
            deviceId = deviceId,
            label = label,
            // Rename must NOT bump the visible "Last synced" timestamp — keep whatever the last
            // real cycle stamped (or, if none, the fallback minted by [nowIso]).
            lastSyncedAt = existing?.lastSyncedAt ?: nowIso(),
            username = username ?: existing?.username,
        )
        return try {
            target.writeDeviceMeta(namespace, deviceId, AnnotationDeviceMetaCodec.encode(meta))
            PublishMetadataResult(rewrittenFiles = 1, failures = 0)
        } catch (_: Exception) {
            PublishMetadataResult(rewrittenFiles = 0, failures = 1)
        }
    }

    /** A row in the Maintenance device-list, post header-hydration. */
    data class DeviceRow(
        val deviceId: String,
        val annotationFileCount: Int,
        val metadata: AnnotationDeviceMeta?,
    )

    /** All namespaces discovered on the target, regardless of which is currently active. */
    suspend fun listNamespaces(): List<NamespaceSummary> {
        val target = targetProvider() ?: return emptyList()
        return try { target.enumerateNamespaces() } catch (_: Exception) { emptyList() }
    }

    /** Bulk-deletes every file under [namespace]. Returns the deleted-file count (0 if no target). */
    suspend fun forgetNamespace(namespace: String): Int {
        val target = targetProvider() ?: return 0
        return try { target.forgetNamespace(namespace) } catch (_: Exception) { 0 }
    }

    /** Empty list when sync is disabled, the namespace has no files, or the listing fails. */
    suspend fun listDevices(namespace: String): List<DeviceRow> {
        val target = targetProvider() ?: return emptyList()
        val listing = try {
            target.enumerateDevices(namespace)
        } catch (_: Exception) {
            return emptyList()
        }
        return listing.devices.map { summary -> hydrate(target, namespace, summary) }
    }

    private suspend fun hydrate(
        target: AnnotationSyncTarget,
        namespace: String,
        summary: DeviceFileSummary,
    ): DeviceRow {
        // Single source of truth: the per-device sentinel. No fallback to per-file headers —
        // a device that hasn't run a sync cycle on the new client legitimately has no
        // "Last synced" yet, and presenting per-file `lastSeenAt` would re-introduce the
        // push-vs-pull dishonesty the sentinel was created to eliminate.
        val metadata = try {
            target.readDeviceMeta(namespace, summary.deviceId)
                ?.let { AnnotationDeviceMetaCodec.decode(it) }
        } catch (_: Exception) {
            null
        }
        return DeviceRow(
            deviceId = summary.deviceId,
            annotationFileCount = summary.annotationFiles.size,
            metadata = metadata,
        )
    }

    /** Result of [forgetDevice]. Surfaces partial-success info to the UI. */
    data class ForgetResult(
        val deletedAnnotationFiles: Int,
        val failures: Int,
    )

    /**
     * Deletes every annotation file belonging to [deviceId] under [namespace], plus that device's
     * metadata sentinel. The sentinel delete is best-effort — failures don't block reporting
     * success on the annotation-file deletes, but they DO count against [ForgetResult.failures]
     * so the user sees the partial outcome. Without the sentinel delete, a forgotten peer that
     * later writes one annotation file would resurrect its row carrying the stale pre-forget
     * label and lastSyncedAt.
     */
    suspend fun forgetDevice(namespace: String, deviceId: String): ForgetResult {
        val target = targetProvider() ?: return ForgetResult(0, failures = 0)
        val listing = try {
            target.enumerateDevices(namespace)
        } catch (_: Exception) {
            return ForgetResult(0, failures = 1)
        }
        val row = listing.devices.firstOrNull { it.deviceId == deviceId }
            ?: return ForgetResult(0, failures = 0)

        var deleted = 0
        var failures = 0
        for (file in row.annotationFiles) {
            try {
                target.delete(namespace, file.itemId, file.filename)
                deleted++
            } catch (_: Exception) {
                failures++
            }
        }
        try {
            target.deleteDeviceMeta(namespace, deviceId)
        } catch (_: Exception) {
            failures++
        }
        return ForgetResult(deleted, failures)
    }

}
