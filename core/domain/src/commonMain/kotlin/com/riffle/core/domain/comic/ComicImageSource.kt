package com.riffle.core.domain.comic

interface ComicImageSource {
    val pageCount: Int
    fun imageBytes(pageIndex: Int): ByteArray
    fun mediaType(pageIndex: Int): String
}
