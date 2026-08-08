package com.riffle.app.feature.reader.cbz

import com.riffle.core.domain.CbzRepository
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.util.Collections
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
 * A 3-entry byte cache eliminates the double-download that [decodeSampledBitmap] triggers
 * (one bounds-only pass + one decode pass on the same page index).
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
) : CbzImageSource {
    override val pageCount: Int get() = count

    private val byteCache: MutableMap<Int, ByteArray> = Collections.synchronizedMap(
        object : LinkedHashMap<Int, ByteArray>(5, 0.75f, true) {
            override fun removeEldestEntry(eldest: Map.Entry<Int, ByteArray>?) = size > 3
        }
    )

    override fun imageBytes(pageIndex: Int): ByteArray = getBytes(pageIndex)

    override fun openStream(pageIndex: Int): InputStream =
        ByteArrayInputStream(getBytes(pageIndex))

    private fun getBytes(pageIndex: Int): ByteArray {
        byteCache[pageIndex]?.let { return it }
        return runBlocking {
            repository.fetchStreamingPageImage(sourceId, itemId, pageIndex, thumbnailWidth)
        }.also { byteCache[pageIndex] = it }
    }
}
