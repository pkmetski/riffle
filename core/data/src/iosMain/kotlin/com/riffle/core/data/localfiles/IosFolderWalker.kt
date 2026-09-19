package com.riffle.core.data.localfiles

import com.riffle.core.domain.DispatcherProvider
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.coroutines.withContext
import platform.Foundation.NSFileManager
import platform.Foundation.NSFileSize
import platform.Foundation.NSFileType
import platform.Foundation.NSFileTypeDirectory
import platform.Foundation.NSNumber
import platform.posix.lstat

@OptIn(ExperimentalForeignApi::class)
class IosFolderWalker(private val dispatchers: DispatcherProvider) {

    /**
     * Walks [folderPath] recursively.
     *
     * Throws when the *root* folder cannot be listed — it was deleted, moved, replaced by a plain
     * file, or lives on a volume that is no longer mounted. This mirrors [SafFolderWalker], which
     * throws when the tree URI no longer resolves, and it matters: `IosLocalFilesScanner` only runs
     * its stale sweep when every folder walked cleanly. Returning an empty list for an unreadable
     * folder would look indistinguishable from "the user emptied the folder", and the sweep would
     * hard-delete every book that folder had ever contributed — library rows, junction rows, and
     * the copied bytes on disk.
     *
     * An unreadable *sub*directory is skipped rather than fatal, matching Android's
     * `DocumentFile.listFiles()`, which returns an empty array for children it cannot enumerate.
     */
    suspend fun walk(folderPath: String): List<IosWalkedFile> = withContext(dispatchers.io) {
        val out = mutableListOf<IosWalkedFile>()
        if (!walkDirectory(folderPath, out)) {
            throw IllegalStateException("Cannot list folder: $folderPath")
        }
        out
    }

    /** Returns false when [dirPath] could not be enumerated at all. */
    private fun walkDirectory(dirPath: String, out: MutableList<IosWalkedFile>): Boolean {
        val manager = NSFileManager.defaultManager

        @Suppress("UNCHECKED_CAST")
        val names = manager.contentsOfDirectoryAtPath(dirPath, error = null) as? List<String> ?: return false
        for (name in names) {
            if (name.startsWith(".")) continue
            val childPath = "$dirPath/$name"
            val attrs = manager.attributesOfItemAtPath(childPath, error = null) ?: continue
            val fileType = attrs[NSFileType] as? String
            if (fileType == NSFileTypeDirectory) {
                walkDirectory(childPath, out)
            } else {
                val size = (attrs[NSFileSize] as? NSNumber)?.longValue ?: 0L
                val mtime = memScoped {
                    val st = alloc<platform.posix.stat>()
                    if (lstat(childPath, st.ptr) == 0) {
                        st.st_mtimespec.tv_sec * 1000L + st.st_mtimespec.tv_nsec / 1_000_000L
                    } else {
                        0L
                    }
                }
                out += IosWalkedFile(
                    path = childPath,
                    displayName = name,
                    sizeBytes = size,
                    mtimeEpochMs = mtime,
                )
            }
        }
        return true
    }
}
