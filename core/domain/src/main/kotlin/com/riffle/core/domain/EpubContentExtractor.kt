package com.riffle.core.domain

import org.w3c.dom.Element
import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream
import javax.xml.parsers.DocumentBuilderFactory

/** The spine chapters and media-overlay clips extracted from one EPUB. */
data class ExtractedEpub(
    val chapters: List<EpubChapterHtml>,
    val smilClips: List<MediaOverlayClip>,
)

/**
 * Pure EPUB-bytes → spine chapters (in reading order) + media-overlay [MediaOverlayClip]s,
 * used to build the cross-EPUB index and the Storyteller fragment map (ADR 0019). Walks
 * `META-INF/container.xml` → OPF → spine, resolving hrefs relative to the OPF directory and
 * following each spine item's `media-overlay` to its SMIL. Returns `null` for input that is
 * not a readable EPUB, so a corrupt download degrades to a deferred build, never a wrong one.
 */
object EpubContentExtractor {

    fun extract(epubBytes: ByteArray): ExtractedEpub? = try {
        val entries = readAllEntries(epubBytes)

        val opfPath = rootfilePath(entries["META-INF/container.xml"]) ?: return null
        val opfDir = opfPath.substringBeforeLast('/', "")
        val opf = parseXml(entries[opfPath] ?: return null) ?: return null

        // manifest: id → (href, mediaOverlayId)
        val manifest = opf.elementsByTag("item").associate { item ->
            item.getAttribute("id") to (item.getAttribute("href") to item.getAttribute("media-overlay"))
        }

        val chapters = mutableListOf<EpubChapterHtml>()
        val clips = mutableListOf<MediaOverlayClip>()
        for (itemref in opf.elementsByTag("itemref")) {
            val idref = itemref.getAttribute("idref")
            val (href, overlayId) = manifest[idref] ?: continue
            val html = entries[resolve(opfDir, href)]?.toString(Charsets.UTF_8) ?: continue
            chapters += EpubChapterHtml(href = href, html = html)

            val overlayHref = overlayId.takeIf { it.isNotEmpty() }?.let { manifest[it]?.first }
            if (overlayHref != null) {
                entries[resolve(opfDir, overlayHref)]?.toString(Charsets.UTF_8)?.let { smilXml ->
                    clips += SmilOverlayParser.parse(smilXml)
                }
            }
        }
        if (chapters.isEmpty()) null else ExtractedEpub(chapters, clips)
    } catch (_: Exception) {
        null
    }

    private fun readAllEntries(bytes: ByteArray): Map<String, ByteArray> {
        val out = LinkedHashMap<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            var entry = zip.nextEntry
            // A non-zip stream yields no entries; the OPF lookup below then fails to null.
            while (entry != null) {
                if (!entry.isDirectory) out[entry.name] = zip.readBytes()
                entry = zip.nextEntry
            }
        }
        return out
    }

    private fun rootfilePath(containerXml: ByteArray?): String? {
        val doc = parseXml(containerXml ?: return null) ?: return null
        return doc.elementsByTag("rootfile").firstOrNull()?.getAttribute("full-path")?.takeIf { it.isNotEmpty() }
    }

    /** Resolve [href] (relative to the OPF dir) to a zip entry path; handles `../` and `./`. */
    private fun resolve(opfDir: String, href: String): String {
        val base = if (opfDir.isEmpty()) emptyList() else opfDir.split('/').toMutableList()
        val segments = base.toMutableList()
        for (part in href.split('/')) {
            when (part) {
                "", "." -> {}
                ".." -> if (segments.isNotEmpty()) segments.removeAt(segments.size - 1)
                else -> segments += part
            }
        }
        return segments.joinToString("/")
    }

    private fun parseXml(bytes: ByteArray): Element? = try {
        DocumentBuilderFactory.newInstance()
            .apply { isNamespaceAware = false }
            .newDocumentBuilder()
            .parse(ByteArrayInputStream(bytes))
            .documentElement
    } catch (_: Exception) {
        null
    }

    private fun Element.elementsByTag(tag: String): List<Element> {
        val nodes = getElementsByTagName(tag)
        return (0 until nodes.length).mapNotNull { nodes.item(it) as? Element }
    }
}
