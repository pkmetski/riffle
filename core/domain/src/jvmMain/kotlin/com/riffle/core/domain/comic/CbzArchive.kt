package com.riffle.core.domain.comic

import java.io.File
import java.io.InputStream
import java.util.zip.ZipFile
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element

/**
 * Reads a CBZ (ZIP-of-images) with random-access. Page order is the archive's image entries in
 * case-insensitive filename order — matches CDisplayEx / Komga / Comixology convention.
 *
 * Only common raster formats are counted as pages. `ComicInfo.xml`, `Thumbs.db`, `__MACOSX/` and
 * anything else non-image is silently skipped, so the reported [pageCount] matches what actually
 * renders.
 */
class CbzArchive(file: File) : ComicArchive {

    private val zip = ZipFile(file)
    private val entries: List<Entry>

    init {
        entries = zip.entries().asSequence()
            .filter { !it.isDirectory }
            .filter { !it.name.contains("__MACOSX", ignoreCase = false) }
            .filter { !it.name.substringAfterLast('/').startsWith(".") }
            .mapNotNull { entry ->
                val ext = entry.name.substringAfterLast('.', "").lowercase()
                val media = IMAGE_EXTENSIONS[ext] ?: return@mapNotNull null
                Entry(entry.name, media)
            }
            .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })
            .toList()
    }

    override val pageCount: Int
        get() = entries.size

    override fun imageBytes(pageIndex: Int): ByteArray {
        val entry = entries[pageIndex]
        val zipEntry = zip.getEntry(entry.name)
            ?: throw IllegalStateException("Missing entry ${entry.name}")
        return zip.getInputStream(zipEntry).use { it.readBytes() }
    }

    override fun openStream(pageIndex: Int): InputStream {
        val entry = entries[pageIndex]
        val zipEntry = zip.getEntry(entry.name)
            ?: throw IllegalStateException("Missing entry ${entry.name}")
        return zip.getInputStream(zipEntry)
    }

    override fun mediaType(pageIndex: Int): String = entries[pageIndex].mediaType

    override fun close() {
        zip.close()
    }

    fun readComicInfo(): List<ComicBookmark>? {
        val entry = zip.entries().asSequence()
            .firstOrNull { e ->
                e.name.equals("ComicInfo.xml", ignoreCase = true) ||
                    e.name.endsWith("/ComicInfo.xml", ignoreCase = true)
            } ?: return null
        return try {
            val doc = zip.getInputStream(entry).use { stream ->
                DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(stream)
            }
            val pageNodes = doc.getElementsByTagName("Page")
            val bookmarks = mutableListOf<ComicBookmark>()
            for (i in 0 until pageNodes.length) {
                val page = pageNodes.item(i) as? Element ?: continue
                val bookmark = page.getAttribute("Bookmark").takeIf { it.isNotBlank() } ?: continue
                val imageIndex = page.getAttribute("Image").toIntOrNull() ?: continue
                bookmarks.add(ComicBookmark(pageIndex = imageIndex, title = bookmark))
            }
            bookmarks.takeIf { it.isNotEmpty() }
        } catch (_: Exception) {
            null
        }
    }

    private data class Entry(val name: String, val mediaType: String)

    companion object {
        private val IMAGE_EXTENSIONS = mapOf(
            "jpg" to "image/jpeg",
            "jpeg" to "image/jpeg",
            "png" to "image/png",
            "gif" to "image/gif",
            "webp" to "image/webp",
            "bmp" to "image/bmp",
        )
    }
}
