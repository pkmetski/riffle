package com.riffle.core.domain.comic

import java.io.Closeable

/**
 * Random-access read of an image-per-entry archive (CBZ today; CBR TBD). Entries are the archive's
 * image entries in filename-sorted order — that ordering IS the page order (Q11 of ADR 0042).
 */
interface ComicArchive : Closeable {
    val pageCount: Int
    fun imageBytes(pageIndex: Int): ByteArray
    fun mediaType(pageIndex: Int): String
}
