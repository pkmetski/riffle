package com.riffle.core.domain

/**
 * Abstraction for storing and retrieving annotation files across different backends.
 *
 * Issue #75 shipped a local-directory backend as a test scaffold; issue #76 added the
 * first network backend, a WebDAV implementation, behind the same interface. Issue #78
 * extended the surface with manual housekeeping (enumerate / delete / sidecars) so the
 * user can forget orphaned devices and compact tombstones.
 *
 * Files are organized per-device at the logical path:
 * ```
 * <namespace>/<itemId>/annotations-<deviceId>.jsonld
 * ```
 *
 * In addition, each device publishes one **sidecar** per namespace describing itself:
 * ```
 * <namespace>/device-<deviceId>.json
 * ```
 *
 * [namespace] is an opaque, **cross-device-stable** identity that scopes a sync target's view
 * of "the same account" — for ABS that is `/api/me` → `user.id`, persisted as
 * [Server.absUserId]. The local Riffle `servers.id` is a per-device random UUID and must
 * **not** be used here: two devices configured against the same ABS server would otherwise
 * write to disjoint paths and never discover each other's files.
 *
 * Each device maintains its own annotation file ([deviceId] is local-only) to enable
 * multi-device sync via a reconciliation layer.
 */
interface AnnotationSyncTarget {

    /**
     * List all annotation files for a given account+item.
     *
     * @return Logical filenames like `annotations-device-A.jsonld`, or empty when the directory
     *     doesn't exist.
     */
    suspend fun list(namespace: String, itemId: String): List<String>

    /** Read a single annotation file. Returns null if it doesn't exist. */
    suspend fun read(namespace: String, itemId: String, filename: String): String?

    /**
     * Write content to an annotation file, atomically overwriting any existing content.
     * Creates the parent directory if needed.
     */
    suspend fun write(namespace: String, itemId: String, filename: String, content: String)

    /**
     * Delete a single annotation file. No-op if the file does not exist.
     *
     * Used by the "forget device" maintenance action to remove a device's per-book files.
     * Implementations MUST NOT throw on a 404-equivalent.
     */
    suspend fun delete(namespace: String, itemId: String, filename: String)

    /**
     * Read a device's sidecar payload (JSON, see `DeviceSidecar`). Returns null if the sidecar
     * doesn't exist — older builds, peers that haven't pushed yet, or after a "forget device"
     * action all produce this null.
     */
    suspend fun readDeviceSidecar(namespace: String, deviceId: String): String?

    /**
     * Write a device's sidecar payload, overwriting any existing content. Best-effort: callers
     * should swallow exceptions so a sidecar publish failure never blocks an annotation push.
     */
    suspend fun writeDeviceSidecar(namespace: String, deviceId: String, content: String)

    /** Delete a device's sidecar. No-op if absent. */
    suspend fun deleteDeviceSidecar(namespace: String, deviceId: String)

    /**
     * Enumerate every device that owns files under [namespace], grouping by `deviceId`.
     *
     * The returned listing covers BOTH per-book annotation files and device sidecars: a device
     * is included if it owns at least one annotation file OR a sidecar. This drives the
     * Maintenance UI (per-device row with file counts) and is the work-list for both
     * forget-device (delete its files) and compact-tombstones (rewrite every annotation file).
     */
    suspend fun enumerateDevices(namespace: String): NamespaceDeviceListing

    /**
     * Enumerate every distinct namespace prefix found under the target's base. Used by the
     * Maintenance UI to surface namespaces other than the currently-active one — typically
     * orphans left behind by a previous namespacing scheme (see commit history around the
     * absUserId migration). Returns counts of annotation files and sidecars per namespace.
     */
    suspend fun enumerateNamespaces(): List<NamespaceSummary>

    /**
     * Delete every file (annotation + sidecar) under [namespace]. Used by the Maintenance
     * "Forget orphan namespace" bulk action. Returns the number of files actually deleted.
     */
    suspend fun forgetNamespace(namespace: String): Int
}

/** Listing returned by [AnnotationSyncTarget.enumerateDevices]. */
data class NamespaceDeviceListing(
    val devices: List<DeviceFileSummary>,
)

/**
 * One row per device discovered under a namespace.
 *
 * @property deviceId The opaque device identifier (UUID minted at install time on each device).
 * @property annotationFiles References to that device's per-book annotation files. Used as the
 *     work-list for "forget device" (delete each) and "compact tombstones" (rewrite each).
 * @property hasSidecar Whether a `device-<deviceId>.json` sidecar is present under the namespace.
 */
data class DeviceFileSummary(
    val deviceId: String,
    val annotationFiles: List<AnnotationFileRef>,
    val hasSidecar: Boolean,
)

/** Reference to a single annotation file under a namespace. */
data class AnnotationFileRef(
    val itemId: String,
    val filename: String,
)

/** Top-level summary of one namespace discovered on the target. */
data class NamespaceSummary(
    val namespace: String,
    val annotationFileCount: Int,
    val sidecarCount: Int,
)
