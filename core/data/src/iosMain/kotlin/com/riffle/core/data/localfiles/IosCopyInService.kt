package com.riffle.core.data.localfiles

import com.riffle.core.domain.DispatcherProvider
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ObjCObjectVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import kotlinx.coroutines.withContext
import platform.Foundation.NSData
import platform.Foundation.NSDocumentDirectory
import platform.Foundation.NSError
import platform.Foundation.NSFileManager
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSUserDomainMask
import platform.Foundation.writeToFile

@OptIn(ExperimentalForeignApi::class)
class IosCopyInService(private val dispatchers: DispatcherProvider) {

    private val documentsDir: String by lazy {
        @Suppress("UNCHECKED_CAST")
        (NSSearchPathForDirectoriesInDomains(NSDocumentDirectory, NSUserDomainMask, true) as List<String>)
            .firstOrNull() ?: error("Cannot resolve iOS documents directory")
    }

    suspend fun copyBook(
        sourceId: String,
        sourceItemId: String,
        extension: String,
        sourcePath: String,
    ): String = withContext(dispatchers.io) {
        val dest = bookPath(sourceId, sourceItemId, extension)
        ensureParent(dest)
        val manager = NSFileManager.defaultManager
        if (manager.fileExistsAtPath(dest)) manager.removeItemAtPath(dest, error = null)
        memScoped {
            val err = alloc<ObjCObjectVar<NSError?>>()
            val ok = manager.copyItemAtPath(sourcePath, toPath = dest, error = err.ptr)
            if (!ok) {
                val desc = err.value?.localizedDescription ?: "unknown error"
                error("Failed to copy $sourcePath → $dest: $desc")
            }
        }
        dest
    }

    suspend fun writeCover(
        sourceId: String,
        sourceItemId: String,
        extension: String,
        bytes: ByteArray,
    ): String = withContext(dispatchers.io) {
        val dest = coverPath(sourceId, sourceItemId, extension)
        ensureParent(dest)
        val manager = NSFileManager.defaultManager
        if (manager.fileExistsAtPath(dest)) manager.removeItemAtPath(dest, error = null)
        bytes.usePinned { pinned ->
            NSData.create(bytes = pinned.addressOf(0), length = bytes.size.toULong())
                .writeToFile(dest, atomically = true)
        }
        dest
    }

    suspend fun deleteBook(sourceId: String, sourceItemId: String) = withContext(dispatchers.io) {
        val dir = sourceDir(sourceId)
        val manager = NSFileManager.defaultManager

        @Suppress("UNCHECKED_CAST")
        val entries = manager.contentsOfDirectoryAtPath(dir, error = null) as? List<String> ?: return@withContext
        for (name in entries) {
            if (name.substringBeforeLast('.') == sourceItemId && !name.contains("covers")) {
                manager.removeItemAtPath("$dir/$name", error = null)
            }
        }
    }

    suspend fun deleteCover(sourceId: String, sourceItemId: String) = withContext(dispatchers.io) {
        val dir = "${sourceDir(sourceId)}/covers"
        val manager = NSFileManager.defaultManager

        @Suppress("UNCHECKED_CAST")
        val entries = manager.contentsOfDirectoryAtPath(dir, error = null) as? List<String> ?: return@withContext
        for (name in entries) {
            if (name.substringBeforeLast('.') == sourceItemId) {
                manager.removeItemAtPath("$dir/$name", error = null)
            }
        }
    }

    private fun sourceDir(sourceId: String) = "$documentsDir/localfiles/$sourceId"
    private fun bookPath(sourceId: String, sourceItemId: String, ext: String) =
        "${sourceDir(sourceId)}/$sourceItemId.$ext"
    private fun coverPath(sourceId: String, sourceItemId: String, ext: String) =
        "${sourceDir(sourceId)}/covers/$sourceItemId.$ext"

    private fun ensureParent(path: String) {
        val parent = path.substringBeforeLast('/')
        NSFileManager.defaultManager.createDirectoryAtPath(
            parent,
            withIntermediateDirectories = true,
            attributes = null,
            error = null,
        )
    }
}
