package com.riffle.core.domain.comic

/**
 * Adapts a local [ComicArchive] (Android `CbzArchive`) to the platform-neutral [ComicPageSource]
 * the shared reader holds. [close] closes the underlying archive's file handle.
 */
class ComicArchivePageSource(private val archive: ComicArchive) : ComicPageSource {
    override val pageCount: Int get() = archive.pageCount
    override fun imageBytes(pageIndex: Int): ByteArray = archive.imageBytes(pageIndex)
    override fun mediaType(pageIndex: Int): String = archive.mediaType(pageIndex)
    override fun close() = archive.close()
}
