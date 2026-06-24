package com.riffle.core.data

import com.riffle.core.domain.AnnotationSyncTarget
import com.riffle.core.domain.DeviceFileSummary
import com.riffle.core.domain.DeviceSidecar

/**
 * Manual housekeeping for the per-device-file annotation-sync model (issue #78).
 *
 * Two user-initiated actions:
 * - [forgetDevice] — DELETE every file owned by a single `deviceId` under one namespace.
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
) {
    /** A row in the Maintenance device-list, post sidecar-hydration. */
    data class DeviceRow(
        val deviceId: String,
        val annotationFileCount: Int,
        val sidecar: DeviceSidecar?,
    )

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
        val sidecar = if (!summary.hasSidecar) null else try {
            target.readDeviceSidecar(namespace, summary.deviceId)?.let(DeviceSidecarCodec::decode)
        } catch (_: Exception) {
            null
        }
        return DeviceRow(
            deviceId = summary.deviceId,
            annotationFileCount = summary.annotationFiles.size,
            sidecar = sidecar,
        )
    }

    /** Result of [forgetDevice]. Surfaces partial-success info to the UI. */
    data class ForgetResult(
        val deletedAnnotationFiles: Int,
        val deletedSidecar: Boolean,
        val failures: Int,
    )

    /** Deletes every annotation file and the sidecar belonging to [deviceId] under [namespace]. */
    suspend fun forgetDevice(namespace: String, deviceId: String): ForgetResult {
        val target = targetProvider()
            ?: return ForgetResult(0, deletedSidecar = false, failures = 0)
        val listing = try {
            target.enumerateDevices(namespace)
        } catch (_: Exception) {
            return ForgetResult(0, deletedSidecar = false, failures = 1)
        }
        val row = listing.devices.firstOrNull { it.deviceId == deviceId }
            ?: return ForgetResult(0, deletedSidecar = false, failures = 0)

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
        var sidecarDeleted = false
        if (row.hasSidecar) {
            try {
                target.deleteDeviceSidecar(namespace, deviceId)
                sidecarDeleted = true
            } catch (_: Exception) {
                failures++
            }
        }
        return ForgetResult(deleted, sidecarDeleted, failures)
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
