package com.riffle.app.feature.reader.highlights

import java.io.File
import java.util.zip.ZipFile

/**
 * [ResourceFetcher] backed by a raw [ZipFile] of the source EPUB. Bypasses Readium's asset
 * pipeline so the elided-view factory can be given real figure bytes even in Highlights mode
 * (where [com.riffle.app.feature.reader.session.ReaderSessionLifecycle.open] never runs and no
 * Readium `Publication` is loaded). Callers construct with the local EPUB file, use in one
 * [com.riffle.app.feature.reader.highlights.HighlightsPublicationFactory.buildHandle] call, then
 * [close] to release the zip descriptor.
 *
 * The incoming [href] is typically an absolute readium_package URL (e.g.
 * `https://readium_package/OEBPS/image_rsrc2HM.jpg`); ZIP entries inside the EPUB are relative
 * paths (`OEBPS/image_rsrc2HM.jpg`). This class strips the scheme+authority prefix if present
 * and any query/fragment before probing the zip.
 */
internal class ZipEpubResourceFetcher private constructor(
    private val zip: ZipFile,
) : ResourceFetcher, AutoCloseable {

    override fun fetch(href: String): ByteArray? {
        val trimmed = href.substringBefore('?').substringBefore('#')
        val stripped = when {
            trimmed.startsWith("https://readium_package/") -> trimmed.removePrefix("https://readium_package/")
            trimmed.startsWith("readium_package://") -> trimmed.removePrefix("readium_package://")
            trimmed.startsWith("/") -> trimmed.removePrefix("/")
            else -> trimmed
        }
        // Percent-decode: Chromium serves `img.src` as a URL-encoded string, but ZIP entry
        // names are raw bytes. Without decoding, `OEBPS/Chapter%203/fig%201.png` misses the
        // real entry `OEBPS/Chapter 3/fig 1.png`. Try the decoded form first, then fall back
        // to the raw string in case the caller already pre-decoded.
        val decoded = runCatching { java.net.URLDecoder.decode(stripped, "UTF-8") }.getOrNull()
        val entry = decoded?.let { zip.getEntry(it) } ?: zip.getEntry(stripped) ?: return null
        return runCatching { zip.getInputStream(entry).use { it.readBytes() } }.getOrNull()
    }

    override fun close() {
        runCatching { zip.close() }
    }

    companion object {
        fun open(epubFile: File): ZipEpubResourceFetcher? =
            runCatching { ZipFile(epubFile) }.getOrNull()?.let(::ZipEpubResourceFetcher)
    }
}
