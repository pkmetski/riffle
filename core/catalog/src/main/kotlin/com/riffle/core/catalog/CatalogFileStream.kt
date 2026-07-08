package com.riffle.core.catalog

import java.io.Closeable
import java.io.InputStream

/**
 * A byte stream over a Catalog item's file, returned by [Catalog.openFile]. Owns the underlying
 * transport resource (network socket, file handle) — the caller must [close] it once done.
 *
 * [contentLength] is `-1` when the source cannot report a length up-front (chunked HTTP responses,
 * pipes) — callers that need a total for progress reporting should treat that as "unknown".
 */
interface CatalogFileStream : Closeable {
    val contentLength: Long
    fun byteStream(): InputStream
}
