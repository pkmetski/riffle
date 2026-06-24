package com.riffle.core.domain

/**
 * Abstraction for storing and retrieving annotation files across different backends.
 *
 * Issue #75 shipped a local-directory backend as a test scaffold; issue #76 adds the
 * first network backend, a WebDAV implementation, behind the same interface.
 *
 * Files are organized per-device at the logical path:
 * ```
 * <serverId>/<itemId>/annotations-<deviceId>.jsonld
 * ```
 *
 * Each device maintains its own annotation file to enable multi-device sync via
 * a reconciliation layer.
 */
interface AnnotationSyncTarget {

    /**
     * List all annotation files for a given server and item.
     *
     * @param serverId The ABS server ID.
     * @param itemId The ABS library item ID.
     * @return List of filenames in the directory, e.g., `["annotations-device-A.jsonld", "annotations-device-B.jsonld"]`.
     *         Returns an empty list if the directory does not exist.
     */
    suspend fun list(serverId: String, itemId: String): List<String>

    /**
     * Read the content of a single annotation file.
     *
     * @param serverId The ABS server ID.
     * @param itemId The ABS library item ID.
     * @param filename The filename to read, e.g., `annotations-device-A.jsonld`.
     * @return The file content as a string, or `null` if the file does not exist.
     */
    suspend fun read(serverId: String, itemId: String, filename: String): String?

    /**
     * Write content to an annotation file, atomically overwriting any existing content.
     *
     * Creates the parent directory if it does not exist. Implementation MUST ensure
     * atomicity to avoid corruption during concurrent writes.
     *
     * @param serverId The ABS server ID.
     * @param itemId The ABS library item ID.
     * @param filename The filename to write, e.g., `annotations-device-A.jsonld`.
     * @param content The file content as a string.
     */
    suspend fun write(serverId: String, itemId: String, filename: String, content: String)
}
