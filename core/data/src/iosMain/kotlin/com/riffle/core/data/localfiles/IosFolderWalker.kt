package com.riffle.core.data.localfiles

import com.riffle.core.domain.DispatcherProvider
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.withContext
import platform.Foundation.NSDate
import platform.Foundation.NSFileManager
import platform.Foundation.NSFileModificationDate
import platform.Foundation.NSFileSize
import platform.Foundation.NSFileType
import platform.Foundation.NSFileTypeDirectory
import platform.Foundation.NSNumber

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
                val mtime = ((attrs[NSFileModificationDate] as? NSDate)
                    ?.timeIntervalSince1970?.times(1000.0))?.toLong() ?: 0L
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
