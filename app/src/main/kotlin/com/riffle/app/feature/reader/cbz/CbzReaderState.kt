package com.riffle.app.feature.reader.cbz

import com.riffle.core.domain.comic.ComicArchive
import java.io.InputStream

/**
 * Wraps a [ComicArchive] behind a stable-identity interface the Composable can hold onto — decorators
 * over the raw archive (thumbnails, prefetch pool) will slot in here without touching the reader.
 */
interface CbzImageSource {
    val pageCount: Int
    /** Raw bytes — for panel detection only. For rendering use [openStream] instead. */
    fun imageBytes(pageIndex: Int): ByteArray
    /** Streaming access to the page image — avoids allocating the full decompressed content in
     *  Java heap, which causes OOM and GC-thrashing ANRs for large BMP/PNG scans. */
    fun openStream(pageIndex: Int): InputStream
}

internal class ArchiveImageSource(private val archive: ComicArchive) : CbzImageSource {
    override val pageCount: Int get() = archive.pageCount
    override fun imageBytes(pageIndex: Int): ByteArray = archive.imageBytes(pageIndex)
    override fun openStream(pageIndex: Int): InputStream = archive.openStream(pageIndex)
}

sealed class CbzReaderState {
    data object Loading : CbzReaderState()
    data class Error(val message: String) : CbzReaderState()
    data class Ready(
        val title: String,
        val pageCount: Int,
        val imageSource: CbzImageSource,
        /** Low-resolution source for the thumbnail strip. Null = use [imageSource] (fast for local archives). */
        val thumbnailSource: CbzImageSource? = null,
    ) : CbzReaderState()
}
