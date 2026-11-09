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
     * When [folderPath] is a bookmark URI (prefixed with "bookmark:"), resolves the bookmark and
     * starts security-scoped access for the duration of the walk. Plain paths (legacy entries)
     * are walked as-is.
     *
     * Throws when the root folder cannot be listed (deleted, moved, permission revoked). Matching
     * [SafFolderWalker]: a walk failure stops the stale sweep so books are not accidentally
     * deleted when access is temporarily revoked.
     *
     * An unreadable sub-directory is skipped rather than fatal, matching Android's behaviour.
     */
    suspend fun walk(folderPath: String): List<IosWalkedFile> = withContext(dispatchers.io) {
        val scopedFolder = IosFolderPicker.resolveBookmark(folderPath)
            ?: throw IllegalStateException("Cannot resolve folder: $folderPath")
        val started = scopedFolder.startAccessing()
        if (!started) throw IllegalStateException("Cannot access security-scoped folder: $folderPath")
        try {
            val out = mutableListOf<IosWalkedFile>()
            if (!walkDirectory(scopedFolder.path, out)) {
                throw IllegalStateException("Cannot list folder: ${scopedFolder.path}")
            }
            out
        } finally {
            scopedFolder.close()
        }
    }

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
