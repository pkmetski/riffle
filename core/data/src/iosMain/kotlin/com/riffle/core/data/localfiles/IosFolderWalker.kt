package com.riffle.core.data.localfiles

import com.riffle.core.domain.DispatcherProvider
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.coroutines.withContext
import okio.FileSystem
import okio.Path.Companion.toPath
import platform.Foundation.NSFileManager
import platform.Foundation.NSFileSize
import platform.Foundation.NSFileType
import platform.Foundation.NSFileTypeDirectory
import platform.Foundation.NSNumber
import platform.posix.lstat

@OptIn(ExperimentalForeignApi::class)
class IosFolderWalker(private val dispatchers: DispatcherProvider) : FolderWalker {

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
    override suspend fun walk(treeUri: String): List<WalkedFile> = withContext(dispatchers.io) {
        val scopedFolder = IosFolderPicker.resolveBookmark(treeUri)
            ?: throw IllegalStateException("Cannot resolve folder: $treeUri")
        val started = scopedFolder.startAccessing()
        if (!started) throw IllegalStateException("Cannot access security-scoped folder: $treeUri")
        try {
            val out = mutableListOf<WalkedFile>()
            if (!walkDirectory(scopedFolder.path, out)) {
                throw IllegalStateException("Cannot list folder: ${scopedFolder.path}")
            }
            out
        } finally {
            scopedFolder.close()
        }
    }

    private fun walkDirectory(dirPath: String, out: MutableList<WalkedFile>): Boolean {
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
                out += WalkedFile(
                    originalUri = childPath,
                    displayName = name,
                    sizeBytes = size,
                    mtimeEpochMs = mtime,
                    openSource = { FileSystem.SYSTEM.source(childPath.toPath()) },
                )
            }
        }
        return true
    }
}
