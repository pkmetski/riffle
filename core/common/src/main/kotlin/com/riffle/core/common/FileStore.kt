package com.riffle.core.common

/**
 * Platform-agnostic abstraction over namespaced on-device file storage.
 *
 * Pure-Kotlin modules use [FileStore] to locate their storage directories without
 * depending on [android.content.Context] or [java.io.File]. The Android implementation
 * roots namespaces under [android.content.Context.getFilesDir]; test implementations
 * can use a temporary directory.
 *
 * Namespaces are short stable identifiers (e.g. "annotation-sync", "epub-cache").
 * Relative paths within a namespace use forward-slash separators.
 */
interface FileStore {
    /**
     * Returns the absolute path string for [namespace]/[relativePath], creating
     * any missing parent directories as a side-effect.
     *
     * When [relativePath] is empty, returns the namespace root directory path.
     */
    fun resolve(namespace: String, relativePath: String = ""): String
}
