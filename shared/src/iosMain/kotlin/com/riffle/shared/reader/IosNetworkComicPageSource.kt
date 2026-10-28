package com.riffle.shared.reader

import com.riffle.core.domain.CbzRepository
import com.riffle.core.domain.comic.ComicPageSource
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * [ComicPageSource] backed by on-demand network fetches via [CbzRepository]. iOS counterpart to
 * Android's `NetworkComicPageSource`.
 *
 * Pass [thumbnailWidth] (e.g. 300) to request a downscaled image — use this for the thumbnail
 * strip to avoid downloading full-resolution pages for small previews.
 *
 * [imageBytes] is synchronous because [ComicPageSource] is a blocking interface consumed from the
 * IO dispatcher in the reader; [runBlocking] on a non-main thread is safe.
 *
 * The byte cache eliminates the double-download the reader's bounds-only + decode passes trigger.
 * [readAheadCount] > 0 prefetches the next pages on an owned scope so page turns during streaming
 * are served from cache rather than waiting for a cold full-resolution download. [close] cancels
 * the scope.
 */
internal class IosNetworkComicPageSource(
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

    private val mutex = Mutex()
    private val byteCache = LinkedHashMap<Int, ByteArray>()
    private val maxCacheEntries = 3 + readAheadCount
    private val inFlight = HashMap<Int, Deferred<ByteArray>>()

    private val readAheadScope: CoroutineScope? =
        if (readAheadCount > 0) CoroutineScope(SupervisorJob() + ioDispatcher) else null

    override fun imageBytes(pageIndex: Int): ByteArray = getBytes(pageIndex)

    override fun mediaType(pageIndex: Int): String = "image/jpeg"

    override fun close() {
        readAheadScope?.cancel()
    }

    private fun getBytes(pageIndex: Int): ByteArray {
        val cached = runBlocking { mutex.withLock { byteCache[pageIndex] } }
        if (cached != null) {
            scheduleReadAhead(pageIndex)
            return cached
        }
        val joined = joinInFlight(pageIndex)
        if (joined != null) {
            scheduleReadAhead(pageIndex)
            return joined
        }
        val fetched = runBlocking {
            repository.fetchStreamingPageImage(sourceId, itemId, pageIndex, thumbnailWidth)
        }
        runBlocking {
            mutex.withLock {
                byteCache[pageIndex] = fetched
                evictIfNeeded()
            }
        }
        scheduleReadAhead(pageIndex)
        return fetched
    }

    private fun joinInFlight(pageIndex: Int): ByteArray? {
        val pending = runBlocking { mutex.withLock { inFlight[pageIndex] } } ?: return null
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
            runBlocking {
                mutex.withLock {
                    if (byteCache.containsKey(target) || inFlight.containsKey(target)) return@withLock
                    inFlight[target] = scope.async {
                        try {
                            repository.fetchStreamingPageImage(sourceId, itemId, target, thumbnailWidth)
                                .also { bytes ->
                                    mutex.withLock {
                                        byteCache[target] = bytes
                                        evictIfNeeded()
                                    }
                                }
                        } finally {
                            mutex.withLock { inFlight.remove(target) }
                        }
                    }
                }
            }
        }
    }

    private fun evictIfNeeded() {
        while (byteCache.size > maxCacheEntries) {
            val eldest = byteCache.iterator()
            if (eldest.hasNext()) {
                eldest.next()
                eldest.remove()
            }
        }
    }
}
