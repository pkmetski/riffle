package com.riffle.app.feature.reader.cbz

import com.riffle.core.domain.CbzRepository
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking

/**
 * [CbzImageSource] backed by on-demand network fetches via [CbzRepository]. Used during the
 * streaming phase (Phase 1) before the full CBZ file has been cached locally.
 *
 * Pass [thumbnailWidth] (e.g. 300) to request a downscaled image from the server — use this for
 * the thumbnail strip to avoid downloading full-resolution pages for small previews.
 *
 * [imageBytes] and [openStream] are synchronous (not suspend) because [CbzImageSource] is a
 * blocking interface consumed from [kotlinx.coroutines.Dispatchers.IO] via produceState in the
 * reader. [runBlocking] on the IO dispatcher is safe — the IO pool is unbounded.
 *
 * The byte cache eliminates the double-download that [decodeSampledBitmap] triggers
 * (one bounds-only pass + one decode pass on the same page index).
 *
 * [readAheadCount] > 0 enables read-ahead: every page access asynchronously prefetches the next
 * [readAheadCount] pages into the byte cache on [readAheadScope]. Without it, a page turn during
 * the streaming phase is a cold synchronous full-resolution download — while the background
 * full-file download is saturating the same link — which the user sees as a multi-second blank
 * page. Read-ahead pipelines the next page's fetch into the dwell time on the current page.
 * Keep it 0 for the thumbnail-strip source: the strip prewarms its own cache sequentially.
 *
 * Once the background download completes, the ViewModel replaces this source with an
 * [ArchiveImageSource] backed by the local [com.riffle.core.domain.comic.CbzArchive].
 */
internal class NetworkImageSource(
    private val sourceId: String,
    private val itemId: String,
    private val count: Int,
    private val repository: CbzRepository,
    private val thumbnailWidth: Int? = null,
    private val readAheadScope: CoroutineScope? = null,
    private val readAheadCount: Int = 0,
    /** Required whenever [readAheadScope] is set — an IO-capable dispatcher for the prefetches. */
    private val readAheadDispatcher: CoroutineDispatcher? = null,
) : CbzImageSource {
    override val pageCount: Int get() = count

    // Holds the current page (fetched twice by decodeSampledBitmap's bounds+decode passes)
    // plus the read-ahead neighbourhood. Access-order LRU so the current page isn't evicted
    // by its own read-ahead.
    private val maxCacheEntries = 3 + readAheadCount
    private val byteCache: MutableMap<Int, ByteArray> = Collections.synchronizedMap(
        object : LinkedHashMap<Int, ByteArray>(5, 0.75f, true) {
            override fun removeEldestEntry(eldest: Map.Entry<Int, ByteArray>?) = size > maxCacheEntries
        }
    )

    // Read-ahead fetches currently in flight, keyed by page index. Deferred (not a Boolean set)
    // so a page turn that arrives mid-prefetch JOINS the in-flight download instead of firing a
    // duplicate cold fetch of the same image — on a link the background full-file download is
    // already saturating, the duplicate is exactly the latency read-ahead exists to remove.
    private val inFlight = ConcurrentHashMap<Int, Deferred<ByteArray>>()

    override fun imageBytes(pageIndex: Int): ByteArray = getBytes(pageIndex)

    override fun openStream(pageIndex: Int): InputStream =
        ByteArrayInputStream(getBytes(pageIndex))

    private fun getBytes(pageIndex: Int): ByteArray {
        val bytes = byteCache[pageIndex]
            ?: joinInFlight(pageIndex)
            ?: runBlocking {
                repository.fetchStreamingPageImage(sourceId, itemId, pageIndex, thumbnailWidth)
            }.also { byteCache[pageIndex] = it }
        scheduleReadAhead(pageIndex)
        return bytes
    }

    /**
     * Awaits an in-flight read-ahead of [pageIndex] if one exists. Safe to block on: the deferred
     * runs on [readAheadDispatcher] (a real thread pool), never on the caller's thread. Returns
     * null when there is nothing in flight or the prefetch failed — the caller then falls back to
     * its own fetch.
     */
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
        val dispatcher = readAheadDispatcher ?: return
        for (offset in 1..readAheadCount) {
            val target = fromIndex + offset
            if (target >= count) break
            if (byteCache.containsKey(target)) continue
            inFlight.computeIfAbsent(target) {
                scope.async(dispatcher) {
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
