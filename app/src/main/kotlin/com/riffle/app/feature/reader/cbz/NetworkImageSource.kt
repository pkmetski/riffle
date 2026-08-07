package com.riffle.app.feature.reader.cbz

import com.riffle.core.domain.CbzRepository
import java.io.ByteArrayInputStream
import java.io.InputStream
import kotlinx.coroutines.runBlocking

/**
 * [CbzImageSource] backed by on-demand network fetches via [CbzRepository]. Used during the
 * streaming phase (Phase 1) before the full CBZ file has been cached locally.
 *
 * [imageBytes] and [openStream] are synchronous (not suspend) because [CbzImageSource] is a
 * blocking interface consumed from [kotlinx.coroutines.Dispatchers.IO] via produceState in the
 * reader. [runBlocking] on the IO dispatcher is safe — the IO pool is unbounded.
 *
 * Once the background download completes, the ViewModel replaces this source with an
 * [ArchiveImageSource] backed by the local [com.riffle.core.domain.comic.CbzArchive].
 */
internal class NetworkImageSource(
    private val sourceId: String,
    private val itemId: String,
    private val count: Int,
    private val repository: CbzRepository,
) : CbzImageSource {
    override val pageCount: Int get() = count

    override fun imageBytes(pageIndex: Int): ByteArray =
        runBlocking { repository.fetchStreamingPageImage(sourceId, itemId, pageIndex) }

    override fun openStream(pageIndex: Int): InputStream =
        ByteArrayInputStream(imageBytes(pageIndex))
}
