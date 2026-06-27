package com.riffle.core.domain

/**
 * Abstraction for storing and retrieving annotation files across different backends.
 *
 * Files are organized per-device at the logical path:
 * ```
 * <namespace>/<itemId>/annotations-<deviceId>.jsonld
 * ```
 *
 * Each per-device file is a JSON array whose first element is a [AnnotationFileHeader] header
 * (label / model / lastSeenAt) followed by the W3C annotation records — see [AnnotationFileHeader]
 * for the format rationale.
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
     * Read the per-device metadata sentinel for [deviceId] under [namespace]. Single JSON object,
     * stored at the namespace root (no `itemId` segment). Returns null when the file does not
     * exist — devices that haven't run a successful sync cycle on the new client never have one.
     */
    suspend fun readDeviceMeta(namespace: String, deviceId: String): String?

    /**
     * Write (overwrite) the per-device metadata sentinel for [deviceId] under [namespace].
     * Called at the end of every successful sync cycle, push or pull-only — see
     * [com.riffle.core.data.AnnotationDeviceMetaCodec] for the body shape.
     */
    suspend fun writeDeviceMeta(namespace: String, deviceId: String, content: String)

    /**
     * Delete the per-device metadata sentinel for [deviceId] under [namespace]. No-op if absent.
     * Called by `forgetDevice` so a forgotten peer leaves no orphan sentinel that could resurrect
     * the row if the same deviceId ever writes another annotation file. Implementations MUST NOT
     * throw on a 404-equivalent.
     */
    suspend fun deleteDeviceMeta(namespace: String, deviceId: String)

    /**
     * Enumerate every device that owns annotation files under [namespace], grouping by
     * `deviceId`. Devices appear iff they own at least one annotation file.
     *
     * This drives the Maintenance UI (per-device row with file counts) and is the work-list
     * for forget-device (delete its files).
     */
    suspend fun enumerateDevices(namespace: String): NamespaceDeviceListing

    /**
     * Enumerate every distinct namespace prefix found under the target's base. Used by the
     * Maintenance UI to surface namespaces other than the currently-active one — typically
     * orphans left behind by a previous namespacing scheme (see commit history around the
     * absUserId migration). Returns counts of annotation files per namespace.
     */
    suspend fun enumerateNamespaces(): List<NamespaceSummary>

    /**
     * Delete every file under [namespace]. Used by the Maintenance "Forget orphan namespace"
     * bulk action. Returns the number of files actually deleted.
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
 *     work-list for "forget device" (delete each).
 */
data class DeviceFileSummary(
    val deviceId: String,
    val annotationFiles: List<AnnotationFileRef>,
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
)
