package com.riffle.app.feature.reader.cbz

import com.riffle.core.domain.comic.ComicArchive

/**
 * Wraps a [ComicArchive] behind a stable-identity interface the Composable can hold onto — decorators
 * over the raw archive (thumbnails, prefetch pool) will slot in here without touching the reader.
 */
interface CbzImageSource {
    val pageCount: Int
    fun imageBytes(pageIndex: Int): ByteArray
}

internal class ArchiveImageSource(private val archive: ComicArchive) : CbzImageSource {
    override val pageCount: Int get() = archive.pageCount
    override fun imageBytes(pageIndex: Int): ByteArray = archive.imageBytes(pageIndex)
}

sealed class CbzReaderState {
    data object Loading : CbzReaderState()
    data class Error(val message: String) : CbzReaderState()
    data class Ready(
        val title: String,
        val pageCount: Int,
        val imageSource: CbzImageSource,
    ) : CbzReaderState()
}
