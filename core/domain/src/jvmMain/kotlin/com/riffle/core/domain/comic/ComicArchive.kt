package com.riffle.core.domain.comic

import java.io.Closeable
import java.io.InputStream

/**
 * Random-access read of an image-per-entry archive (CBZ today; CBR TBD). Entries are the archive's
 * image entries in filename-sorted order — that ordering IS the page order (Q11 of ADR 0050).
 */
interface ComicArchive : Closeable {
    val pageCount: Int
    /** Returns the raw compressed bytes of the page image. Use for panel detection only — for
     *  rendering, prefer [openStream] to avoid allocating the full decompressed image in heap. */
    fun imageBytes(pageIndex: Int): ByteArray
    /** Opens a streaming [InputStream] for the page image. Caller must close the stream. */
    fun openStream(pageIndex: Int): InputStream
    fun mediaType(pageIndex: Int): String
}
