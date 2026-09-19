package com.riffle.core.data.localfiles

import com.riffle.core.domain.localfiles.LocalFilesFolderHealthCheckerInterface
import kotlinx.cinterop.BooleanVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.value
import platform.Foundation.NSFileManager

/**
 * iOS [LocalFilesFolderHealthCheckerInterface]. A folder is "healthy" when its path still resolves
 * to a readable directory.
 *
 * Android checks the SAF persisted-URI grant list, because there the folder is a tree URI whose
 * grant the user can revoke in system Settings. iOS local folders are plain filesystem paths (see
 * [IosFolderWalker]), so the equivalent failure is the directory having been moved or deleted —
 * e.g. an external volume unmounted, or an iCloud/Files location no longer present.
 *
 * As on Android, an unhealthy folder degrades to "we can't rescan it for new files", not "your
 * books disappeared": already-copied bytes live in app-private storage and stay readable.
 */
@OptIn(ExperimentalForeignApi::class)
class IosLocalFilesFolderHealthChecker : LocalFilesFolderHealthCheckerInterface {

    override fun healthFor(treeUris: Collection<String>): Map<String, Boolean> {
        if (treeUris.isEmpty()) return emptyMap()
        return treeUris.associateWith { isHealthy(it) }
    }

    /** True when [path] is an existing, readable directory. */
    fun isHealthy(path: String): Boolean {
        val manager = NSFileManager.defaultManager
        val isDirectory = memScoped {
            val flag = alloc<BooleanVar>()
            val exists = manager.fileExistsAtPath(path, isDirectory = flag.ptr)
            exists && flag.value
        }
        return isDirectory && manager.isReadableFileAtPath(path)
    }
}
