package com.riffle.core.data

import com.riffle.core.domain.AnnotationSyncTarget
import com.riffle.core.domain.DeviceFileSummary
import com.riffle.core.domain.DeviceMetadata
import com.riffle.core.domain.NamespaceSummary
import java.time.Instant

/**
 * Manual housekeeping for the per-device-file annotation-sync model (issue #78).
 *
 * Two user-initiated actions:
 * - [forgetDevice] — DELETE every annotation file owned by a single `deviceId` under one
 *   namespace, plus any legacy `device-<deviceId>.json` sidecar left over from earlier builds.
 *   Safe under the all-mirrors-everything push model because each peer's file already carries
 *   the same records; the only risk is data loss from devices that went offline before any
 *   other device synced their last edits.
 * - [compactTombstones] — for every annotation file in the namespace, strip `riffle:deleted=true`
 *   records and PUT the result back. Only safe when every device is online and fully synced —
 *   the UI must show that warning before invoking this.
 *
 * Operates against the controller's active target (gracefully no-ops when sync is disabled).
 */
class AnnotationSyncMaintenance(
    private val targetProvider: () -> AnnotationSyncTarget?,
    private val nowIso: () -> String = { Instant.now().toString() },
) {
    /**
     * Refresh this device's [DeviceMetadata] header across every annotation file it owns under
     * [namespace]. Used after a rename so peer devices see the new label without waiting for
     * the next annotation push. Best-effort: per-file failures are swallowed.
     */
    suspend fun publishDeviceMetadata(
        namespace: String,
        deviceId: String,
        label: String,
        model: String,
    ) {
        val target = targetProvider() ?: return
        val listing = try { target.enumerateDevices(namespace) } catch (_: Exception) { return }
        val row = listing.devices.firstOrNull { it.deviceId == deviceId } ?: return
        val metadata = DeviceMetadata(
            deviceId = deviceId,
            label = label,
            model = model,
            lastSeenAt = nowIso(),
        )
        for (file in row.annotationFiles) {
            try {
                val body = target.read(namespace, file.itemId, file.filename) ?: continue
                val rewritten = DeviceMetadataCodec.replaceHeader(body, metadata)
                if (rewritten != body) {
                    target.write(namespace, file.itemId, file.filename, rewritten)
                }
            } catch (_: Exception) {
                // per-file failure — continue with the rest
            }
        }
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

    /** Result of [compactTombstones]. */
    data class CompactResult(
        val filesScanned: Int,
        val filesRewritten: Int,
        val tombstonesRemoved: Int,
        val failures: Int,
    )

    /**
     * For every annotation file under [namespace], reads the body, strips tombstones, and PUTs
     * the rewritten body back. Files with zero tombstones are skipped (no PUT — preserves
     * mtimes). The action is target-wide and rewrites peer files, by design — see issue #78
     * design notes.
     */
    suspend fun compactTombstones(namespace: String): CompactResult {
        val target = targetProvider()
            ?: return CompactResult(0, 0, 0, 0)
        val listing = try {
            target.enumerateDevices(namespace)
        } catch (_: Exception) {
            return CompactResult(0, 0, 0, 1)
        }
        var scanned = 0
        var rewritten = 0
        var removed = 0
        var failures = 0
        for (device in listing.devices) {
            for (file in device.annotationFiles) {
                scanned++
                try {
                    val body = target.read(namespace, file.itemId, file.filename) ?: continue
                    val result = TombstoneCompactor.compact(body)
                    if (result.removed == 0) continue
                    target.write(namespace, file.itemId, file.filename, result.newContent)
                    rewritten++
                    removed += result.removed
                } catch (_: Exception) {
                    failures++
                }
            }
        }
        return CompactResult(scanned, rewritten, removed, failures)
    }
}
