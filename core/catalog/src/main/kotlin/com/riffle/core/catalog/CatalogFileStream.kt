package com.riffle.core.catalog

import io.ktor.utils.io.ByteReadChannel

/**
 * A byte stream over a Catalog item's file, scoped to [Catalog.withFileStream].
 * Owns the underlying transport resource; the Catalog closes it after the callback returns.
 *
 * [contentLength] is `-1` when the source cannot report a length up-front (chunked HTTP responses,
 * pipes) — callers that need a total for progress reporting should treat that as "unknown".
 */
interface CatalogFileStream {
    val contentLength: Long
    val channel: ByteReadChannel
}
