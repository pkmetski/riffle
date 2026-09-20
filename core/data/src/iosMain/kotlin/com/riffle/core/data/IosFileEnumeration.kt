package com.riffle.core.data

import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSDirectoryEnumerator
import platform.Foundation.NSFileManager
import platform.Foundation.NSFileSize

/**
 * NSFileManager's answer to `File.walkTopDown()` and `File.length()`.
 *
 * Both halves existed three times over in `iosMain` — `IosArtifactStores` had
 * `enumerateRelativePaths`/`fileSizeAt`, `IosContentCacheArtifactScannerImpl` had
 * `relativePathsUnder`/`fileSize`, and `IosAudiobookFiles.directorySize` inlined a third copy of
 * the NSNumber unboxing. They are the foundation every downloads and cache listing on iOS stands
 * on, so a divergence between them is a divergence in what the user sees is stored.
 */
@OptIn(ExperimentalForeignApi::class)
internal object IosFileEnumeration {

    /** Every path under [root], relative to it, recursively. Empty when [root] does not exist. */
    fun relativePathsUnder(root: String): List<String> {
        val manager = NSFileManager.defaultManager
        if (!manager.fileExistsAtPath(root)) return emptyList()
        val enumerator: NSDirectoryEnumerator = manager.enumeratorAtPath(root) ?: return emptyList()
        val out = mutableListOf<String>()
        while (true) {
            out += enumerator.nextObject() as? String ?: break
        }
        return out
    }

    /** Size in bytes of the file at [path], or 0 when it is absent or unreadable. */
    fun fileSize(path: String): Long {
        val attributes = NSFileManager.defaultManager.attributesOfItemAtPath(path, error = null) ?: return 0L
        return attributes[NSFileSize].toLongOrZero()
    }

    /** Total size in bytes of every file under [path], recursively. 0 when absent. */
    fun directorySize(path: String): Long =
        relativePathsUnder(path).sumOf { fileSize("$path/$it") }
}

/**
 * `attributesOfItemAtPath` returns `NSNumber` values boxed as `Any?`; Kotlin/Native maps NSNumber
 * to the matching Kotlin primitive, so the size can arrive as Long, Int, ULong or UInt depending
 * on the value.
 */
private fun Any?.toLongOrZero(): Long = when (this) {
    is Long -> this
    is Int -> this.toLong()
    is ULong -> this.toLong()
    is UInt -> this.toLong()
    is Number -> this.toLong()
    else -> 0L
}
