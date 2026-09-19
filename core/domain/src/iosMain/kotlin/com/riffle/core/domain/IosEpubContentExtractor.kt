package com.riffle.core.domain

import com.fleeksoft.ksoup.Ksoup
import com.fleeksoft.ksoup.parser.Parser

/**
 * iOS port of jvmMain's [EpubContentExtractor] (ADR 0023): EPUB bytes → spine chapters in reading
 * order plus their media-overlay clips, the input the cross-EPUB index and the Storyteller
 * fragment map are built from.
 *
 * Identical walk to the JVM version — `META-INF/container.xml` → OPF → spine, hrefs resolved
 * relative to the OPF directory, each spine item's `media-overlay` followed to its SMIL — and the
 * same "null for anything that isn't a readable EPUB" contract, so a corrupt download degrades to
 * a deferred build rather than a wrong index. Only the zip and XML layers differ: [IosZipArchive]
 * instead of `java.util.zip`, ksoup instead of `DocumentBuilderFactory`, and
 * [IosSmilOverlayParser] for the overlays.
 *
 * [IosZipArchive] reads entries by name from the central directory, so — like the JVM `File`
 * overload and unlike its whole-stream one — a synced bundle's hundreds of MB of audio are never
 * materialised; only the OPF, spine chapters and SMIL entries are inflated.
 */
object IosEpubContentExtractor {

    fun extract(epubBytes: ByteArray): ExtractedEpub? = try {
        val archive = IosZipArchive(epubBytes)
        extractFrom { archive.readEntry(it) }
    } catch (_: Exception) {
        null
    }

    private fun extractFrom(lookup: (String) -> ByteArray?): ExtractedEpub? = try {
        val opfPath = rootfilePath(lookup("META-INF/container.xml")) ?: return null
        val opfDir = opfPath.substringBeforeLast('/', "")
        val opf = parseXml(lookup(opfPath) ?: return null) ?: return null

        // manifest: id → (href, mediaOverlayId)
        val manifest = opf.getElementsByTag("item").associate { item ->
            item.attr("id") to (item.attr("href") to item.attr("media-overlay"))
        }

        val chapters = mutableListOf<EpubChapterHtml>()
        val clips = mutableListOf<MediaOverlayClip>()
        for (itemref in opf.getElementsByTag("itemref")) {
            val idref = itemref.attr("idref")
            val (href, overlayId) = manifest[idref] ?: continue
            val html = lookup(resolve(opfDir, href))?.decodeToString() ?: continue
            chapters += EpubChapterHtml(href = href, html = html)

            val overlayHref = overlayId.takeIf { it.isNotEmpty() }?.let { manifest[it]?.first }
            if (overlayHref != null) {
                lookup(resolve(opfDir, overlayHref))?.decodeToString()?.let { smilXml ->
                    clips += IosSmilOverlayParser.parse(smilXml)
                }
            }
        }
        if (chapters.isEmpty()) null else ExtractedEpub(chapters, clips)
    } catch (_: Exception) {
        null
    }

    private fun rootfilePath(containerXml: ByteArray?): String? {
        val doc = parseXml(containerXml ?: return null) ?: return null
        return doc.getElementsByTag("rootfile").firstOrNull()?.attr("full-path")?.takeIf { it.isNotEmpty() }
    }

    /** Resolve [href] (relative to the OPF dir) to a zip entry path; handles `../` and `./`. */
    private fun resolve(opfDir: String, href: String): String {
        val base = if (opfDir.isEmpty()) emptyList() else opfDir.split('/')
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

    private fun parseXml(bytes: ByteArray) = try {
        Ksoup.parse(html = bytes.decodeToString(), parser = Parser.xmlParser())
    } catch (_: Exception) {
        null
    }
}
