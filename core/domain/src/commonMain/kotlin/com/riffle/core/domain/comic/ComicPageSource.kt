package com.riffle.core.domain.comic

/**
 * A [ComicImageSource] the reader holds for the lifetime of a session and releases when done.
 * Local archive-backed sources hold a file handle (Android `ZipFile`); network sources may hold a
 * read-ahead coroutine scope. [close] releases whatever the backing source owns; the default is a
 * no-op for stateless sources (e.g. in-memory byte archives).
 *
 * Platform-neutral so the shared `CbzReaderViewModel` can hold it without knowing whether the pages
 * come from a local `CbzArchive`, a Komga stream, or an in-memory iOS archive.
 */
interface ComicPageSource : ComicImageSource {
    fun close() {}

    /**
     * Decode retry budget for a single page. Local archive-backed sources decode deterministically
     * (1 attempt); network-streaming sources return >1 so a transient fetch failure mid-stream is
     * retried rather than surfaced as a blank page.
     */
    val decodeRetries: Int get() = 1
}
