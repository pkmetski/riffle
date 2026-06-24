package com.riffle.core.domain

/**
 * Abstraction for storing and retrieving annotation files across different backends.
 *
 * Issue #75 shipped a local-directory backend as a test scaffold; issue #76 added the
 * first network backend, a WebDAV implementation, behind the same interface.
 *
 * Files are organized per-device at the logical path:
 * ```
 * <namespace>/<itemId>/annotations-<deviceId>.jsonld
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
     * @param namespace Cross-device-stable account identity. See class kdoc.
     * @param itemId The ABS library item ID.
     * @return List of filenames in the directory, e.g., `["annotations-device-A.jsonld", "annotations-device-B.jsonld"]`.
     *         Returns an empty list if the directory does not exist.
     */
    suspend fun list(namespace: String, itemId: String): List<String>

    /**
     * Read the content of a single annotation file.
     *
     * @param namespace Cross-device-stable account identity. See class kdoc.
     * @param itemId The ABS library item ID.
     * @param filename The filename to read, e.g., `annotations-device-A.jsonld`.
     * @return The file content as a string, or `null` if the file does not exist.
     */
    suspend fun read(namespace: String, itemId: String, filename: String): String?

    /**
     * Write content to an annotation file, atomically overwriting any existing content.
     *
     * Creates the parent directory if it does not exist. Implementation MUST ensure
     * atomicity to avoid corruption during concurrent writes.
     *
     * @param namespace Cross-device-stable account identity. See class kdoc.
     * @param itemId The ABS library item ID.
     * @param filename The filename to write, e.g., `annotations-device-A.jsonld`.
     * @param content The file content as a string.
     */
    suspend fun write(namespace: String, itemId: String, filename: String, content: String)
}
