package com.riffle.core.data

import com.riffle.core.common.FileStore
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readAvailable
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.Foundation.NSData
import platform.Foundation.NSFileHandle
import platform.Foundation.NSFileManager
import platform.Foundation.closeFile
import platform.Foundation.create
import platform.Foundation.dataWithContentsOfFile
import platform.Foundation.fileHandleForWritingAtPath
import platform.Foundation.writeData
import platform.Foundation.writeToFile
import platform.posix.memcpy

/**
 * The one definition of the per-item file layout every iOS store uses —
 * `<namespace>/<sourceId>/<itemId><extension>` — plus the file primitives over it. Used by
 * `IosFileArtifactStore` (downloads/cache enumeration), the CBZ repository and the source files
 * cleaner so the layout cannot drift between writers and readers (#1101).
 */
@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
object IosItemFiles {

    fun relativePath(sourceId: String, itemId: String, extension: String): String = "$sourceId/$itemId$extension"

    fun path(fileStore: FileStore, namespace: String, sourceId: String, itemId: String, extension: String): String =
        fileStore.resolve(namespace, relativePath(sourceId, itemId, extension))

    fun exists(path: String): Boolean = NSFileManager.defaultManager.fileExistsAtPath(path)

    fun size(path: String): Long = IosFileEnumeration.fileSize(path)

    fun readBytes(path: String): ByteArray? {
        val data = NSData.dataWithContentsOfFile(path) ?: return null
        val out = ByteArray(data.length.toInt())
        if (out.isNotEmpty()) {
            out.usePinned { pinned -> memcpy(pinned.addressOf(0), data.bytes, data.length) }
        }
        return out
    }

    /** Atomically writes [bytes] to [path], creating the parent directory. False when the write failed. */
    fun writeBytes(path: String, bytes: ByteArray): Boolean {
        mkdirsForFile(path)
        val data = if (bytes.isEmpty()) {
            NSData()
        } else {
            bytes.usePinned { pinned -> NSData.create(bytes = pinned.addressOf(0), length = bytes.size.toULong()) }
        }
        return data.writeToFile(path, atomically = true)
    }

    /**
     * Streams [channel] into a temporary sibling of [path] and renames it into place once the
     * stream ends, reporting cumulative progress against [totalBytes] (or the bytes seen so far
     * when the length is unknown). Returns false — and leaves nothing behind — on any failure.
     */
    suspend fun writeChannel(
        path: String,
        channel: ByteReadChannel,
        totalBytes: Long,
        onProgress: (downloaded: Long, total: Long) -> Unit = { _, _ -> },
    ): Boolean {
        mkdirsForFile(path)
        val partial = "$path.part"
        val manager = NSFileManager.defaultManager
        manager.removeItemAtPath(partial, error = null)
        if (!manager.createFileAtPath(partial, contents = null, attributes = null)) return false
        val handle = NSFileHandle.fileHandleForWritingAtPath(partial) ?: return false
        var written = 0L
        try {
            val buffer = ByteArray(CHUNK_BYTES)
            while (true) {
                val read = channel.readAvailable(buffer, 0, buffer.size)
                if (read <= 0) break
                val data = buffer.usePinned { pinned -> NSData.create(bytes = pinned.addressOf(0), length = read.toULong()) }
                handle.writeData(data)
                written += read
                onProgress(written, if (totalBytes > 0) totalBytes else written)
            }
            handle.closeFile()
            if (written == 0L) {
                manager.removeItemAtPath(partial, error = null)
                return false
            }
            return move(partial, path)
        } catch (t: Throwable) {
            runCatching { handle.closeFile() }
            manager.removeItemAtPath(partial, error = null)
            throw t
        }
    }

    fun delete(path: String) {
        NSFileManager.defaultManager.removeItemAtPath(path, error = null)
    }

    /** Move [from] to [to], replacing any existing file at [to]. */
    fun move(from: String, to: String): Boolean {
        mkdirsForFile(to)
        val manager = NSFileManager.defaultManager
        manager.removeItemAtPath(to, error = null)
        return manager.moveItemAtPath(from, toPath = to, error = null)
    }

    private fun mkdirsForFile(path: String) {
        NSFileManager.defaultManager.createDirectoryAtPath(
            path.substringBeforeLast('/'),
            withIntermediateDirectories = true,
            attributes = null,
            error = null,
        )
    }

    private const val CHUNK_BYTES = 256 * 1024
}
