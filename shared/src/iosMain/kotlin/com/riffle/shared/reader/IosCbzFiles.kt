package com.riffle.shared.reader

import com.riffle.core.common.FileStore
import com.riffle.core.data.NS_CBZ_CACHE
import com.riffle.core.data.NS_CBZ_DOWNLOADS
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.Foundation.NSData
import platform.Foundation.NSFileManager
import platform.Foundation.create
import platform.Foundation.dataWithContentsOfFile
import platform.Foundation.writeToFile
import platform.posix.memcpy

/**
 * On-disk layout of the iOS CBZ stores — `<namespace>/<sourceId>/<itemId>.cbz` under
 * [NS_CBZ_DOWNLOADS] (user-pinned) and [NS_CBZ_CACHE] (background cache), the same layout
 * `IosDownloadsRepositoryImpl`, `IosContentCacheArtifactScannerImpl` and
 * `IosLibraryItemOfflineAvailabilityImpl` already enumerate. Mirrors Android's `LocalStore`
 * pair inside `CbzRepositoryImpl` (#1101 — iOS had the namespaces but never wrote to them).
 */
@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
internal class IosCbzFiles(private val fileStore: FileStore) {

    fun downloadPath(sourceId: String, itemId: String): String =
        fileStore.resolve(NS_CBZ_DOWNLOADS, "$sourceId/$itemId$EXTENSION")

    fun cachePath(sourceId: String, itemId: String): String =
        fileStore.resolve(NS_CBZ_CACHE, "$sourceId/$itemId$EXTENSION")

    fun exists(path: String): Boolean = NSFileManager.defaultManager.fileExistsAtPath(path)

    fun readBytes(path: String): ByteArray? {
        val data = NSData.dataWithContentsOfFile(path) ?: return null
        val out = ByteArray(data.length.toInt())
        if (out.isNotEmpty()) {
            out.usePinned { pinned -> memcpy(pinned.addressOf(0), data.bytes, data.length) }
        }
        return out
    }

    /** Writes [bytes] to [path], creating the `<sourceId>` directory. False when the write failed. */
    fun writeBytes(path: String, bytes: ByteArray): Boolean {
        val manager = NSFileManager.defaultManager
        val parent = path.substringBeforeLast('/')
        manager.createDirectoryAtPath(parent, withIntermediateDirectories = true, attributes = null, error = null)
        val data = bytes.usePinned { pinned ->
            NSData.create(bytes = pinned.addressOf(0), length = bytes.size.toULong())
        }
        return data.writeToFile(path, atomically = true)
    }

    fun delete(path: String) {
        NSFileManager.defaultManager.removeItemAtPath(path, error = null)
    }

    /** Move [from] to [to], replacing any existing file at [to]. */
    fun move(from: String, to: String): Boolean {
        val manager = NSFileManager.defaultManager
        manager.createDirectoryAtPath(to.substringBeforeLast('/'), withIntermediateDirectories = true, attributes = null, error = null)
        manager.removeItemAtPath(to, error = null)
        return manager.moveItemAtPath(from, toPath = to, error = null)
    }

    companion object {
        const val EXTENSION = ".cbz"
    }
}
