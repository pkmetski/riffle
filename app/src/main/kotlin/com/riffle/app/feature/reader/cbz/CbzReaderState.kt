package com.riffle.app.feature.reader.cbz

import com.riffle.core.domain.comic.ComicPageSource

sealed class CbzReaderState {
    data object Loading : CbzReaderState()
    data class Error(val message: String) : CbzReaderState()
    data class Ready(
        val title: String,
        val pageCount: Int,
        val imageSource: ComicPageSource,
        /** Low-resolution source for the thumbnail strip. Null = use [imageSource] (fast for local archives). */
        val thumbnailSource: ComicPageSource? = null,
    ) : CbzReaderState()
}
