package com.riffle.core.domain

import org.w3c.dom.Element
import java.io.ByteArrayInputStream
import java.io.File
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Pulls the Dublin Core metadata + cover image out of an EPUB. Pure JVM. Sibling to
 * [EpubContentExtractor] — shares the container.xml → OPF walk pattern but returns a small
 * metadata record instead of chapter HTML. Never throws on malformed input; a broken zip or a
 * missing OPF yields [EpubMetadata.EMPTY].
 */
object EpubMetadataExtractor {

    fun extract(epubBytes: ByteArray): EpubMetadata {
        val entries = try { readAllEntries(epubBytes) } catch (_: Exception) { return EpubMetadata.EMPTY }
        return extractFrom { entries[it] }
    }

    fun extract(epub: File): EpubMetadata = try {
        ZipFile(epub).use { zip ->
            extractFrom { name -> zip.getEntry(name)?.let { zip.getInputStream(it).use { s -> s.readBytes() } } }
        }
    } catch (_: Exception) {
        EpubMetadata.EMPTY
    }

    private fun extractFrom(lookup: (String) -> ByteArray?): EpubMetadata = try {
        val containerBytes = lookup("META-INF/container.xml") ?: return EpubMetadata.EMPTY
        val opfPath = rootfilePath(containerBytes) ?: return EpubMetadata.EMPTY
        val opfDir = opfPath.substringBeforeLast('/', "")
        val opfBytes = lookup(opfPath) ?: return EpubMetadata.EMPTY
        val opf = parseXml(opfBytes) ?: return EpubMetadata.EMPTY

        val metadataEl = opf.firstElementByTag("metadata")
        val title = metadataEl?.firstDcText("title")
        val author = metadataEl?.collectDcText("creator")?.joinToString(", ")?.ifEmpty { null }
        val language = metadataEl?.firstDcText("language")
        val publisher = metadataEl?.firstDcText("publisher")
        val publishedYear = metadataEl?.firstDcText("date")?.let { parseYear(it) }
        val description = metadataEl?.firstDcText("description")
        val (isbn, asin) = metadataEl?.let { parseIdentifiers(it) } ?: (null to null)
        val genres = metadataEl?.collectDcText("subject") ?: emptyList()
        val seriesName = metadataEl?.let { parseSeries(it) }

        // Cover: EPUB3 (manifest item with properties="cover-image") preferred, EPUB2 (<meta
        // name="cover" content="X"> → manifest[id=X]) fallback.
        val (coverBytes, coverExt) = findCover(opf, opfDir, lookup)

        EpubMetadata(
            title = title,
            author = author,
            language = language,
            publisher = publisher,
            publishedYear = publishedYear,
            description = description,
            isbn = isbn,
            asin = asin,
            seriesName = seriesName,
            genres = genres,
            coverBytes = coverBytes,
            coverExtension = coverExt,
        )
    } catch (_: Exception) {
        EpubMetadata.EMPTY
    }

    private fun parseIdentifiers(metadata: Element): Pair<String?, String?> {
        var isbn: String? = null
        var asin: String? = null
        for (id in metadata.dcElementsByTag("identifier")) {
            val value = id.textContent?.trim().orEmpty()
            if (value.isEmpty()) continue
            val scheme = id.getAttribute("opf:scheme").ifEmpty { id.getAttribute("scheme") }.lowercase()
            when {
                scheme.contains("isbn") -> if (isbn == null) isbn = value
                scheme.contains("asin") -> if (asin == null) asin = value
                // Some publishers embed a scheme-less identifier of the form "urn:isbn:…".
                value.startsWith("urn:isbn:", ignoreCase = true) && isbn == null ->
                    isbn = value.substringAfter(':').substringAfter(':')
            }
        }
        return isbn to asin
    }

    private fun parseSeries(metadata: Element): String? {
        // EPUB3: <meta property="belongs-to-collection" refines="…">Name</meta> with a sibling
        // <meta property="collection-type" refines="#id">series</meta>. We accept any collection
        // if we can't confirm the type, since many publishers omit collection-type entirely.
        val collections = metadata.elementsByTag("meta")
            .filter { it.getAttribute("property") == "belongs-to-collection" }
        if (collections.isEmpty()) {
            // EPUB2 (calibre): <meta name="calibre:series" content="Name">.
            val calibre = metadata.elementsByTag("meta")
                .firstOrNull { it.getAttribute("name") == "calibre:series" }
            return calibre?.getAttribute("content")?.ifEmpty { null }
        }
        // Prefer a collection whose sibling collection-type is 'series'.
        val idToType = metadata.elementsByTag("meta")
            .filter { it.getAttribute("property") == "collection-type" }
            .associate { it.getAttribute("refines").removePrefix("#") to it.textContent.trim().lowercase() }
        val series = collections.firstOrNull { c ->
            val id = c.getAttribute("id")
            idToType[id] == "series"
        } ?: collections.first()
        return series.textContent?.trim()?.ifEmpty { null }
    }

    private fun findCover(
        opf: Element,
        opfDir: String,
        lookup: (String) -> ByteArray?,
    ): Pair<ByteArray?, String?> {
        val manifest = opf.firstElementByTag("manifest") ?: return null to null
        val items = manifest.elementsByTag("item")

        // EPUB3
        val e3 = items.firstOrNull { it.getAttribute("properties").split(' ').contains("cover-image") }
        val e3Href = e3?.getAttribute("href")?.ifEmpty { null }
        if (e3Href != null) {
            val bytes = lookup(resolve(opfDir, e3Href))
            if (bytes != null) return bytes to extensionOf(e3Href)
        }

        // EPUB2
        val metaCover = opf.firstElementByTag("metadata")?.elementsByTag("meta")
            ?.firstOrNull { it.getAttribute("name") == "cover" }
            ?.getAttribute("content")?.ifEmpty { null }
        if (metaCover != null) {
            val item = items.firstOrNull { it.getAttribute("id") == metaCover }
            val href = item?.getAttribute("href")?.ifEmpty { null }
            if (href != null) {
                val bytes = lookup(resolve(opfDir, href))
                if (bytes != null) return bytes to extensionOf(href)
            }
        }
        return null to null
    }

    private fun parseYear(raw: String): String? {
        // dc:date is often ISO-8601; keep just the 4-digit year prefix when present.
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return null
        val yearMatch = Regex("""^(\d{4})""").find(trimmed) ?: return null
        return yearMatch.value
    }

    private fun extensionOf(href: String): String? {
        val name = href.substringAfterLast('/', href)
        val dot = name.lastIndexOf('.')
        return if (dot in 1 until name.length - 1) name.substring(dot + 1).lowercase() else null
    }

    private fun readAllEntries(bytes: ByteArray): Map<String, ByteArray> {
        val out = LinkedHashMap<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                if (!entry.isDirectory) out[entry.name] = zip.readBytes()
                entry = zip.nextEntry
            }
        }
        return out
    }

    private fun rootfilePath(containerXml: ByteArray): String? {
        val doc = parseXml(containerXml) ?: return null
        return doc.elementsByTag("rootfile").firstOrNull()?.getAttribute("full-path")?.takeIf { it.isNotEmpty() }
    }

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

    private fun Element.firstElementByTag(tag: String): Element? = elementsByTag(tag).firstOrNull()

    private fun Element.firstText(tag: String): String? =
        elementsByTag(tag).firstOrNull()?.textContent?.trim()?.ifEmpty { null }

    private fun Element.collectText(tag: String): List<String> =
        elementsByTag(tag).mapNotNull { it.textContent?.trim()?.ifEmpty { null } }

    /**
     * OPF's Dublin Core metadata elements always carry the `dc:` prefix in real files, but Room
     * fixtures / stripped exports sometimes use the un-prefixed form. Namespace-unaware parsing
     * treats the qualified name as literal, so we look for both.
     */
    private fun Element.dcElementsByTag(local: String): List<Element> =
        elementsByTag("dc:$local") + elementsByTag(local)

    private fun Element.firstDcText(local: String): String? =
        dcElementsByTag(local).firstOrNull()?.textContent?.trim()?.ifEmpty { null }

    private fun Element.collectDcText(local: String): List<String> =
        dcElementsByTag(local).mapNotNull { it.textContent?.trim()?.ifEmpty { null } }
}
