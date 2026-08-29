package com.riffle.app.feature.reader.cbz

import com.riffle.core.domain.CbzRepository
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
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
    private val readAheadDispatcher: CoroutineDispatcher = Dispatchers.IO,
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

    // Pages with a read-ahead fetch currently in flight, so rapid page turns don't stack
    // duplicate downloads of the same index.
    private val inFlight: MutableSet<Int> = ConcurrentHashMap.newKeySet()

    override fun imageBytes(pageIndex: Int): ByteArray = getBytes(pageIndex)

    override fun openStream(pageIndex: Int): InputStream =
        ByteArrayInputStream(getBytes(pageIndex))

    private fun getBytes(pageIndex: Int): ByteArray {
        val bytes = byteCache[pageIndex] ?: runBlocking {
            repository.fetchStreamingPageImage(sourceId, itemId, pageIndex, thumbnailWidth)
        }.also { byteCache[pageIndex] = it }
        scheduleReadAhead(pageIndex)
        return bytes
    }

    private fun scheduleReadAhead(fromIndex: Int) {
        val scope = readAheadScope ?: return
        for (offset in 1..readAheadCount) {
            val target = fromIndex + offset
            if (target >= count) break
            if (byteCache.containsKey(target)) continue
            if (!inFlight.add(target)) continue
            scope.launch(readAheadDispatcher) {
                try {
                    byteCache[target] = repository.fetchStreamingPageImage(sourceId, itemId, target, thumbnailWidth)
                } catch (_: Throwable) {
                    // Best-effort: a failed prefetch just leaves the page to the on-demand path.
                } finally {
                    inFlight.remove(target)
                }
            }
        }
    }
}
