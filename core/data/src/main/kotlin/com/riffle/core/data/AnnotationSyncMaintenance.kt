package com.riffle.core.data

import com.riffle.core.domain.AnnotationSyncTarget
import com.riffle.core.domain.DeviceFileSummary
import com.riffle.core.domain.DeviceMetadata
import com.riffle.core.domain.NamespaceSummary
import java.time.Instant

/**
 * Manual housekeeping for the per-device-file annotation-sync model (issue #78).
 *
 * Single user-initiated action: [forgetDevice] — DELETE every annotation file owned by a single
 * `deviceId` under one namespace, plus any legacy `device-<deviceId>.json` sidecar left over
 * from earlier builds. Safe under the all-mirrors-everything push model because each peer's
 * file already carries the same records; the only risk is data loss from devices that went
 * offline before any other device synced their last edits.
 *
 * A tombstone-compaction action was prototyped and removed — see ADR 0025 for the rationale.
 * Operates against the controller's active target (gracefully no-ops when sync is disabled).
 */
class AnnotationSyncMaintenance(
    private val targetProvider: () -> AnnotationSyncTarget?,
    private val nowIso: () -> String = { Instant.now().toString() },
) {
    /** Outcome of [publishDeviceMetadata] — lets the UI report partial failure to the user. */
    data class PublishMetadataResult(
        val rewrittenFiles: Int,
        val failures: Int,
    )

    /**
     * Refresh this device's [DeviceMetadata] header across every annotation file it owns under
     * [namespace]. Used after a rename so peer devices see the new label without waiting for
     * the next annotation push. Preserves each file's existing `lastSeenAt` so a rename does not
     * masquerade as a fresh push — the hint stays a real signal of when this device last
     * actually pushed. Returns counts so the caller can surface partial failure.
     */
    suspend fun publishDeviceMetadata(
        namespace: String,
        deviceId: String,
        label: String,
    ): PublishMetadataResult {
        val target = targetProvider() ?: return PublishMetadataResult(0, 0)
        val listing = try {
            target.enumerateDevices(namespace)
        } catch (_: Exception) {
            return PublishMetadataResult(0, 1)
        }
        val row = listing.devices.firstOrNull { it.deviceId == deviceId }
            ?: return PublishMetadataResult(0, 0)

        // Mint a single fallback timestamp for files that don't yet have a header. This only fires
        // on legacy files written before the embed-header refactor; current files always have one.
        val fallbackLastSeenAt = nowIso()
        var rewritten = 0
        var failures = 0
        for (file in row.annotationFiles) {
            try {
                val body = target.read(namespace, file.itemId, file.filename) ?: continue
                val existing = DeviceMetadataCodec.extractHeader(body)
                val metadata = DeviceMetadata(
                    deviceId = deviceId,
                    label = label,
                    lastSeenAt = existing?.lastSeenAt ?: fallbackLastSeenAt,
                )
                val newBody = DeviceMetadataCodec.replaceHeader(body, metadata)
                if (newBody != body) {
                    target.write(namespace, file.itemId, file.filename, newBody)
                    rewritten++
                }
            } catch (_: Exception) {
                failures++
            }
        }
        return PublishMetadataResult(rewritten, failures)
    }

    /** A row in the Maintenance device-list, post header-hydration. */
    data class DeviceRow(
        val deviceId: String,
        val annotationFileCount: Int,
        val metadata: DeviceMetadata?,
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
        // Read the header from the first available annotation file. Stop at the first parse — every
        // file owned by this device carries the same metadata (refreshed atomically with each push).
        val metadata = summary.annotationFiles.firstNotNullOfOrNull { ref ->
            try {
                target.read(namespace, ref.itemId, ref.filename)
                    ?.let { DeviceMetadataCodec.extractHeader(it) }
            } catch (_: Exception) {
                null
            }
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
        val deletedLegacySidecar: Boolean,
        val failures: Int,
    )

    /**
     * Deletes every annotation file belonging to [deviceId] under [namespace], plus any legacy
     * `device-<deviceId>.json` sidecar file from earlier builds. The legacy delete is
     * opportunistic — current builds don't write sidecars, but older clients in the household
     * may still publish them.
     */
    suspend fun forgetDevice(namespace: String, deviceId: String): ForgetResult {
        val target = targetProvider()
            ?: return ForgetResult(0, deletedLegacySidecar = false, failures = 0)
        val listing = try {
            target.enumerateDevices(namespace)
        } catch (_: Exception) {
            return ForgetResult(0, deletedLegacySidecar = false, failures = 1)
        }
        val row = listing.devices.firstOrNull { it.deviceId == deviceId }
            ?: return ForgetResult(0, deletedLegacySidecar = false, failures = 0)

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
        var legacySidecarDeleted = false
        if (row.hasLegacySidecar) {
            try {
                target.deleteDeviceSidecar(namespace, deviceId)
                legacySidecarDeleted = true
            } catch (_: Exception) {
                failures++
            }
        }
        return ForgetResult(deleted, legacySidecarDeleted, failures)
    }

}
