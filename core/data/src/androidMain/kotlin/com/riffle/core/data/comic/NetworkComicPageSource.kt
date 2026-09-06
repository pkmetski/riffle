package com.riffle.core.data.comic

import com.riffle.core.domain.CbzRepository
import com.riffle.core.domain.comic.ComicPageSource
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking

/**
 * [ComicPageSource] backed by on-demand network fetches via [CbzRepository]. Used during the
 * streaming phase (before the full CBZ is cached locally).
 *
 * Pass [thumbnailWidth] (e.g. 300) to request a downscaled image from the server — use this for the
 * thumbnail strip to avoid downloading full-resolution pages for small previews.
 *
 * [imageBytes] is synchronous (not suspend) because [ComicImageSource] is a blocking interface
 * consumed from an IO dispatcher in the reader; [runBlocking] on the IO pool is safe (unbounded).
 *
 * The byte cache eliminates the double-download the reader's bounds-only + decode passes trigger.
 *
 * [readAheadCount] > 0 enables read-ahead: every page access asynchronously prefetches the next
 * [readAheadCount] pages into the byte cache on an internally-owned scope. Without it, a page turn
 * during streaming is a cold synchronous full-resolution download racing the background full-file
 * download for bandwidth — a multi-second blank page. [close] cancels the read-ahead scope; the
 * reader calls it when the source is replaced or the session ends.
 */
internal class NetworkComicPageSource(
    private val sourceId: String,
    private val itemId: String,
    private val count: Int,
    private val repository: CbzRepository,
    private val thumbnailWidth: Int? = null,
    private val readAheadCount: Int = 0,
    ioDispatcher: CoroutineDispatcher,
) : ComicPageSource {
    override val pageCount: Int get() = count

    override val decodeRetries: Int get() = 3

    private val readAheadScope: CoroutineScope? =
        if (readAheadCount > 0) CoroutineScope(SupervisorJob() + ioDispatcher) else null

    private val maxCacheEntries = 3 + readAheadCount
    private val byteCache: MutableMap<Int, ByteArray> = Collections.synchronizedMap(
        object : LinkedHashMap<Int, ByteArray>(5, 0.75f, true) {
            override fun removeEldestEntry(eldest: Map.Entry<Int, ByteArray>?) = size > maxCacheEntries
        },
    )

    private val inFlight = ConcurrentHashMap<Int, Deferred<ByteArray>>()

    override fun imageBytes(pageIndex: Int): ByteArray = getBytes(pageIndex)

    override fun mediaType(pageIndex: Int): String = "image/jpeg"

    override fun close() {
        readAheadScope?.cancel()
    }

    private fun getBytes(pageIndex: Int): ByteArray {
        val bytes = byteCache[pageIndex]
            ?: joinInFlight(pageIndex)
            ?: runBlocking {
                repository.fetchStreamingPageImage(sourceId, itemId, pageIndex, thumbnailWidth)
            }.also { byteCache[pageIndex] = it }
        scheduleReadAhead(pageIndex)
        return bytes
    }

    private fun joinInFlight(pageIndex: Int): ByteArray? {
        val pending = inFlight[pageIndex] ?: return null
        return try {
            runBlocking { pending.await() }
        } catch (_: Throwable) {
            null
        }
    }

    private fun scheduleReadAhead(fromIndex: Int) {
        val scope = readAheadScope ?: return
        for (offset in 1..readAheadCount) {
            val target = fromIndex + offset
            if (target >= count) break
            if (byteCache.containsKey(target)) continue
            inFlight.computeIfAbsent(target) {
                scope.async {
                    try {
                        repository.fetchStreamingPageImage(sourceId, itemId, target, thumbnailWidth)
                            .also { byteCache[target] = it }
                    } finally {
                        inFlight.remove(target)
                    }
                }
            }
        }
    }
}
