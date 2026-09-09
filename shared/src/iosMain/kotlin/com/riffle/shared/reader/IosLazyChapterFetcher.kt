package com.riffle.shared.reader

import com.riffle.core.catalog.LazyPublicationCapability
import com.riffle.core.catalog.LazyPublicationShape
import com.riffle.core.catalog.oreilly.OReillyEpub
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import platform.Foundation.NSCachesDirectory
import platform.Foundation.NSData
import platform.Foundation.NSFileManager
import platform.Foundation.NSProcessInfo
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSUserDomainMask
import platform.Foundation.create

/**
 * Obj-C-compatible seam for on-demand O'Reilly chapter fetching from the iOS reader.
 *
 * Swift creates an [OReillyLazyContainer] (Readium `Container`) that calls back into this
 * interface when Readium requests a chapter or asset byte stream.  All methods are callback-based
 * rather than suspend so they can be called from Swift without coroutine machinery.
 *
 * Completion callbacks are invoked on the **main thread**.
 */
interface IosLazyChapterFetcher {
    /**
     * Ensure the XHTML for [fullPath] is on disk (fetching + assembling if necessary), then call
     * [completion] with the absolute file-system path, or null on failure.
     */
    fun fetchChapterXhtmlPath(fullPath: String, expectedByteSize: Long, completion: (String?) -> Unit)

    /**
     * Ensure the binary asset at [fullPath] is on disk (fetching if necessary), then call
     * [completion] with the absolute file-system path, or null on failure.
     */
    fun fetchAssetPath(fullPath: String, completion: (String?) -> Unit)

    /**
     * Background-warm the chapter after [currentIndex] so the next page turn feels instant.
     * No-op if already cached or index is out of range.
     */
    fun prefetchNext(currentIndex: Int)

    /** Cancel all in-flight fetches and release the internal coroutine scope. */
    fun dispose()
}

/**
 * Production implementation: fetches raw HTML from [cap] for [itemId], assembles XHTML via the
 * shared [OReillyEpub] helpers, and writes to the iOS caches directory with atomic rename semantics.
 * Assets are cached similarly at a parallel path.
 */
@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
class IosLazyChapterFetcherImpl(private val cap: LazyPublicationCapability, private val shape: LazyPublicationShape, private val itemId: String,) :
    IosLazyChapterFetcher {

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    override fun fetchChapterXhtmlPath(fullPath: String, expectedByteSize: Long, completion: (String?) -> Unit) {
        scope.launch {
            val path = getOrFetchChapter(fullPath, expectedByteSize)
            withContext(Dispatchers.Main) { completion(path) }
        }
    }

    override fun fetchAssetPath(fullPath: String, completion: (String?) -> Unit) {
        scope.launch {
            val path = getOrFetchAsset(fullPath)
            withContext(Dispatchers.Main) { completion(path) }
        }
    }

    override fun prefetchNext(currentIndex: Int) {
        val nextItem = shape.spine.getOrNull(currentIndex + 1) ?: return
        val cachePath = chapterCachePath(nextItem.fullPath)
        if (NSFileManager.defaultManager.fileExistsAtPath(cachePath)) return
        scope.launch {
            runCatching { getOrFetchChapter(nextItem.fullPath, nextItem.declaredByteSize) }
        }
    }

    override fun dispose() {
        scope.cancel()
    }

    private suspend fun getOrFetchChapter(fullPath: String, expectedByteSize: Long): String? {
        val path = chapterCachePath(fullPath)
        if (NSFileManager.defaultManager.fileExistsAtPath(path)) return path
        val html = cap.fetchChapterForLazy(itemId, fullPath, expectedByteSize) ?: return null
        val spineItem = shape.spine.firstOrNull { it.fullPath == fullPath } ?: return null
        val xhtml = OReillyEpub.buildChapterXhtml(shape, spineItem, html)
        return writeToCache(path, xhtml.encodeToByteArray())
    }

    private suspend fun getOrFetchAsset(fullPath: String): String? {
        val path = assetCachePath(fullPath)
        if (NSFileManager.defaultManager.fileExistsAtPath(path)) return path
        val bytes = cap.fetchAssetForLazy(itemId, fullPath) ?: return null
        return writeToCache(path, bytes)
    }

    private fun chapterCachePath(fullPath: String): String =
        "${cacheBase()}/chapters/${sanitize(fullPath)}"

    private fun assetCachePath(fullPath: String): String =
        "${cacheBase()}/assets/${sanitize(fullPath)}"

    private fun cacheBase(): String =
        "${cachesDirectory()}/riffle_oreilly_lazy/${sanitize(shape.bookId)}"

    private fun writeToCache(path: String, bytes: ByteArray): String? {
        val parent = path.substringBeforeLast("/")
        NSFileManager.defaultManager.createDirectoryAtPath(
            parent, withIntermediateDirectories = true, attributes = null, error = null,
        )
        val tmpPath = "$path.${NSProcessInfo.processInfo.globallyUniqueString()}.tmp"
        val nsData = bytes.usePinned { pinned ->
            NSData.create(bytes = pinned.addressOf(0), length = bytes.size.toULong())
        }
        NSFileManager.defaultManager.createFileAtPath(tmpPath, contents = nsData, attributes = null)
        val moved = NSFileManager.defaultManager.moveItemAtPath(tmpPath, toPath = path, error = null)
        if (!moved) {
            // Fallback: overwrite directly (non-atomic, but better than nothing)
            NSFileManager.defaultManager.createFileAtPath(path, contents = nsData, attributes = null)
        }
        return if (NSFileManager.defaultManager.fileExistsAtPath(path)) path else null
    }

    private fun sanitize(s: String): String = s.replace(Regex("[^A-Za-z0-9_.\\-]"), "_")

    private fun cachesDirectory(): String {
        @Suppress("UNCHECKED_CAST")
        return (NSSearchPathForDirectoriesInDomains(NSCachesDirectory, NSUserDomainMask, true) as List<String>)
            .firstOrNull() ?: NSTemporaryDirectory()
    }

    companion object {
        /**
         * Serialize [shape] to JSON for passing across the Kotlin↔Swift bridge.
         * Decoded on the Swift side by [OReillyPublicationBuilder].
         */
        fun serializeShape(shape: LazyPublicationShape): String = buildString {
            append("{")
            append("\"bookId\":${shape.bookId.jsonStr()},")
            append("\"identifier\":${shape.identifier.jsonStr()},")
            append("\"title\":${shape.title.jsonStr()},")
            append("\"language\":${shape.language.jsonStr()},")
            append("\"absoluteFilesPrefix\":${shape.absoluteFilesPrefix.jsonStr()},")
            append("\"pathFilesPrefix\":${shape.pathFilesPrefix.jsonStr()},")
            append("\"cssFullPaths\":[${shape.cssFullPaths.joinToString(",") { it.jsonStr() }}],")
            append("\"spine\":[${shape.spine.joinToString(",") { item ->
                "{\"index\":${item.index}," +
                    "\"fullPath\":${item.fullPath.jsonStr()}," +
                    "\"title\":${item.title.jsonStr()}," +
                    "\"declaredByteSize\":${item.declaredByteSize}}"
            }}]")
            append("}")
        }

        private fun String.jsonStr(): String {
            val escaped = buildString {
                for (ch in this@jsonStr) {
                    when (ch) {
                        '\\' -> append("\\\\")
                        '"' -> append("\\\"")
                        '\n' -> append("\\n")
                        '\r' -> append("\\r")
                        '\t' -> append("\\t")
                        else -> if (ch.code < 0x20) append("\\u${ch.code.toString(16).padStart(4, '0')}") else append(ch)
                    }
                }
            }
            return "\"$escaped\""
        }
    }
}
