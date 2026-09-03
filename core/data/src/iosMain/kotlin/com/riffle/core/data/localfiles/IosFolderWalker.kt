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

    suspend fun walk(folderPath: String): List<IosWalkedFile> = withContext(dispatchers.io) {
        val out = mutableListOf<IosWalkedFile>()
        walkDirectory(folderPath, out)
        out
    }

    private fun walkDirectory(dirPath: String, out: MutableList<IosWalkedFile>) {
        val manager = NSFileManager.defaultManager

        @Suppress("UNCHECKED_CAST")
        val names = manager.contentsOfDirectoryAtPath(dirPath, error = null) as? List<String> ?: return
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
    }
}
