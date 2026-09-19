package com.riffle.core.data

import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.Foundation.NSData
import platform.Foundation.NSDirectoryEnumerator
import platform.Foundation.NSFileManager
import platform.Foundation.NSFileSize
import platform.Foundation.NSString
import platform.Foundation.NSURL
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.create
import platform.Foundation.dataWithContentsOfFile
import platform.Foundation.stringWithContentsOfFile
import platform.Foundation.writeToFile
import platform.posix.memcpy

/**
 * NSFileManager helpers for the iOS audiobook download/cache repositories — the counterpart to the
 * `java.io.File` calls in `AudiobookDownloadRepositoryImpl`/`AudiobookCacheRepositoryImpl`. Kept in
 * one place so both repositories agree on directory layout, recursive sizing and deletion.
 *
 * The on-disk layout matches Android exactly (`<root>/<sourceId>/<itemId>/{track-N, manifest.json}`)
 * and the manifest format is the shared [AudiobookDownloadManifest], so a book downloaded on one
 * platform has the same shape as on the other.
 */
@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
internal object IosAudiobookFiles {

    fun itemDir(root: String, sourceId: String, itemId: String): String = "$root/$sourceId/$itemId"

    fun manifestPath(itemDir: String): String = "$itemDir/manifest.json"

    fun exists(path: String): Boolean = NSFileManager.defaultManager.fileExistsAtPath(path)

    fun mkdirs(path: String) {
        NSFileManager.defaultManager.createDirectoryAtPath(
            path,
            withIntermediateDirectories = true,
            attributes = null,
            error = null,
        )
    }

    fun deleteRecursively(path: String) {
        NSFileManager.defaultManager.removeItemAtPath(path, error = null)
    }

    /** `file://` URL for a track file, the form the iOS player feeds to AVPlayer. */
    fun fileUrl(path: String): String = NSURL.fileURLWithPath(path).absoluteString ?: "file://$path"

    fun readText(path: String): String? =
        NSString.stringWithContentsOfFile(path, encoding = NSUTF8StringEncoding, error = null)

    fun writeText(path: String, text: String): Boolean =
        (text as NSString).writeToFile(path, atomically = true, encoding = NSUTF8StringEncoding, error = null)

    fun writeBytes(path: String, bytes: ByteArray): Boolean {
        val data = if (bytes.isEmpty()) {
            NSData()
        } else {
            bytes.usePinned { pinned -> NSData.create(bytes = pinned.addressOf(0), length = bytes.size.toULong()) }
        }
        return data.writeToFile(path, atomically = false)
    }

    fun readBytes(path: String): ByteArray? {
        val data = NSData.dataWithContentsOfFile(path) ?: return null
        val out = ByteArray(data.length.toInt())
        if (out.isNotEmpty()) {
            out.usePinned { pinned -> memcpy(pinned.addressOf(0), data.bytes, data.length) }
        }
        return out
    }

    /** Total size in bytes of every file under [path], recursively. 0 when absent. */
    fun directorySize(path: String): Long {
        val manager = NSFileManager.defaultManager
        if (!manager.fileExistsAtPath(path)) return 0L
        val enumerator: NSDirectoryEnumerator = manager.enumeratorAtPath(path) ?: return 0L
        var total = 0L
        while (true) {
            val relative = enumerator.nextObject() as? String ?: break
            val attributes = manager.attributesOfItemAtPath("$path/$relative", error = null) ?: continue
            total += (attributes[NSFileSize] as? NSNumberLike)?.toLongOrZero() ?: 0L
        }
        return total
    }

    /** Moves [from] onto [to], replacing any existing item. Returns false when the move failed. */
    fun move(from: String, to: String): Boolean {
        val manager = NSFileManager.defaultManager
        manager.removeItemAtPath(to, error = null)
        val parent = to.substringBeforeLast('/', "")
        if (parent.isNotEmpty()) mkdirs(parent)
        return manager.moveItemAtPath(from, toPath = to, error = null)
    }
}

/**
 * `attributesOfItemAtPath` returns `NSNumber` values boxed as `Any?`; Kotlin/Native maps NSNumber to
 * the matching Kotlin primitive, so the size can arrive as Long, Int or ULong depending on value.
 */
private typealias NSNumberLike = Any

private fun NSNumberLike.toLongOrZero(): Long = when (this) {
    is Long -> this
    is Int -> this.toLong()
    is ULong -> this.toLong()
    is UInt -> this.toLong()
    is Number -> this.toLong()
    else -> 0L
}
