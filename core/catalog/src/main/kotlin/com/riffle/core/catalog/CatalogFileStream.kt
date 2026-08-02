package com.riffle.core.catalog

import java.io.Closeable
import java.io.InputStream

/**
 * A byte stream over a Catalog item's file, scoped to [Catalog.withFileStream]. Owns the underlying
 * transport resource (network socket, file handle); the Catalog closes it after the callback.
 *
 * [contentLength] is `-1` when the source cannot report a length up-front (chunked HTTP responses,
 * pipes) — callers that need a total for progress reporting should treat that as "unknown".
 */
interface CatalogFileStream : Closeable {
    val contentLength: Long
    fun byteStream(): InputStream
}
