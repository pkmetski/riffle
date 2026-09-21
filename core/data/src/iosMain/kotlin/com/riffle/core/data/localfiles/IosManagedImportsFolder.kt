package com.riffle.core.data.localfiles

import com.riffle.core.domain.DispatcherProvider
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ObjCObjectVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.value
import kotlinx.coroutines.withContext
import platform.Foundation.NSDocumentDirectory
import platform.Foundation.NSError
import platform.Foundation.NSFileManager
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSUserDomainMask

/**
 * The iOS "Open in Riffle" destination: `<Documents>/Riffle Imports`.
 *
 * Lives under `Documents` rather than in the app's caches so the folder survives an eviction
 * sweep and so `UIFileSharingEnabled` exposes it in the Files app — a user who dropped a book in
 * via Finder can find it again, and can remove it there.
 *
 * The directory is walkable by [IosFolderWalker] as a plain path, so it registers as an ordinary
 * Local Files folder with no special case anywhere in the scanner.
 */
@OptIn(ExperimentalForeignApi::class)
class IosManagedImportsFolder(
    private val dispatchers: DispatcherProvider,
) : ManagedImportsFolder {

    private val documentsDir: String by lazy {
        @Suppress("UNCHECKED_CAST")
        (NSSearchPathForDirectoriesInDomains(NSDocumentDirectory, NSUserDomainMask, true) as List<String>)
            .firstOrNull() ?: error("Cannot resolve iOS documents directory")
    }

    private val path: String get() = "$documentsDir/${ManagedImportsFolders.FOLDER_NAME}"

    override suspend fun ensure(): FolderUri = withContext(dispatchers.io) {
        val manager = NSFileManager.defaultManager
        if (!manager.fileExistsAtPath(path)) {
            memScoped {
                val err = alloc<ObjCObjectVar<NSError?>>()
                val ok = manager.createDirectoryAtPath(
                    path,
                    withIntermediateDirectories = true,
                    attributes = null,
                    error = err.ptr,
                )
                if (!ok) error("Cannot create $path: ${err.value?.localizedDescription ?: "unknown error"}")
            }
        }
        FolderUri(path)
    }

    override suspend fun place(locator: String, displayName: String): String = withContext(dispatchers.io) {
        val manager = NSFileManager.defaultManager

        @Suppress("UNCHECKED_CAST")
        val existing = (manager.contentsOfDirectoryAtPath(path, error = null) as? List<String>).orEmpty().toSet()
        val name = OpenInFileTypes.uniqueNameIn(existing, displayName)
        val dest = "$path/$name"
        memScoped {
            val err = alloc<ObjCObjectVar<NSError?>>()
            val ok = manager.copyItemAtPath(locator, toPath = dest, error = err.ptr)
            if (!ok) {
                error("Cannot copy $locator → $dest: ${err.value?.localizedDescription ?: "unknown error"}")
            }
        }
        name
    }
}
