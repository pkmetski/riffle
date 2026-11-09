package com.riffle.core.domain

import com.fleeksoft.ksoup.Ksoup
import com.fleeksoft.ksoup.nodes.Element
import com.fleeksoft.ksoup.parser.Parser
import com.riffle.core.models.EpubMetadata
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.Foundation.NSData
import platform.Foundation.dataWithContentsOfFile
import platform.posix.memcpy

@OptIn(ExperimentalForeignApi::class)
object IosEpubMetadataExtractor {

    fun extract(epubPath: String): EpubMetadata {
        val data = NSData.dataWithContentsOfFile(epubPath) ?: return EpubMetadata.EMPTY
        val bytes = ByteArray(data.length.toInt())
        if (bytes.isEmpty()) return EpubMetadata.EMPTY
        bytes.usePinned { pinned -> memcpy(pinned.addressOf(0), data.bytes, data.length) }
        return extract(bytes)
    }

    fun extract(epubBytes: ByteArray): EpubMetadata = try {
        val archive = IosZipArchive(epubBytes)
        extractFrom { archive.readEntry(it) }
    } catch (_: Exception) {
        EpubMetadata.EMPTY
    }

    private fun extractFrom(lookup: (String) -> ByteArray?): EpubMetadata = try {
        val containerXml = lookup("META-INF/container.xml") ?: return EpubMetadata.EMPTY
        val opfPath = rootfilePath(containerXml) ?: return EpubMetadata.EMPTY
        val opfDir = opfPath.substringBeforeLast('/', "")
        val opfBytes = lookup(opfPath) ?: return EpubMetadata.EMPTY
        val opf = parseXml(opfBytes) ?: return EpubMetadata.EMPTY

        val epubVersion = opf.attr("version").ifEmpty { null }
        val metadataEl = opf.getElementsByTag("metadata").firstOrNull()

        val title = metadataEl?.firstDcText("title")
        val author = metadataEl?.collectDcText("creator")?.joinToString(", ")?.ifEmpty { null }
        val language = metadataEl?.firstDcText("language")
        val publisher = metadataEl?.firstDcText("publisher")
        val publishedYear = metadataEl?.firstDcText("date")?.let { parseYear(it) }
        val description = metadataEl?.firstDcText("description")
        val (isbn, asin) = metadataEl?.let { parseIdentifiers(it) } ?: (null to null)
        val genres = metadataEl?.collectDcText("subject") ?: emptyList()
        val (seriesName, seriesSequence) = metadataEl?.let { parseSeries(it) } ?: (null to null)
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
            seriesSequence = seriesSequence,
            genres = genres,
            coverBytes = coverBytes,
            coverExtension = coverExt,
            epubVersion = epubVersion,
        )
    } catch (_: Exception) {
        EpubMetadata.EMPTY
    }

    private fun parseIdentifiers(metadata: Element): Pair<String?, String?> {
        var isbn: String? = null
        var asin: String? = null
        for (id in metadata.dcElementsByTag("identifier")) {
            val value = id.text().trim().ifEmpty { continue }
            val scheme = (id.attr("opf:scheme").ifEmpty { id.attr("scheme") }).lowercase()
            when {
                scheme.contains("isbn") -> if (isbn == null) isbn = value
                scheme.contains("asin") -> if (asin == null) asin = value
                value.startsWith("urn:isbn:", ignoreCase = true) && isbn == null ->
                    isbn = value.substringAfter(':').substringAfter(':')
            }
        }
        return isbn to asin
    }

    private fun parseSeries(metadata: Element): Pair<String?, String?> {
        val collections = metadata.getElementsByTag("meta")
            .filter { it.attr("property") == "belongs-to-collection" }
        if (collections.isEmpty()) {
            val name = metadata.getElementsByTag("meta")
                .firstOrNull { it.attr("name") == "calibre:series" }
                ?.attr("content")?.ifEmpty { null }
                ?: return null to null
            val sequence = metadata.getElementsByTag("meta")
                .firstOrNull { it.attr("name") == "calibre:series_index" }
                ?.attr("content")?.trim()?.ifEmpty { null }
            return name to sequence
        }
        val refinesToType = metadata.getElementsByTag("meta")
            .filter { it.attr("property") == "collection-type" }
            .mapNotNull { m ->
                val refinesId = m.attr("refines").removePrefix("#").ifEmpty { null } ?: return@mapNotNull null
                refinesId to m.text().trim().lowercase()
            }
            .toMap()
        val series = collections.firstOrNull { c ->
            val id = c.attr("id").ifEmpty { null } ?: return@firstOrNull false
            refinesToType[id] == "series"
        } ?: collections.first()
        val name = series.text().trim().ifEmpty { null } ?: return null to null
        val seriesId = series.attr("id").ifEmpty { null }
        val sequence = seriesId?.let { id ->
            metadata.getElementsByTag("meta").firstOrNull {
                it.attr("property") == "group-position" &&
                    it.attr("refines").removePrefix("#") == id
            }
        }?.text()?.trim()?.ifEmpty { null }
        return name to sequence
    }

    private fun findCover(opf: Element, opfDir: String, lookup: (String) -> ByteArray?): Pair<ByteArray?, String?> {
        val manifest = opf.getElementsByTag("manifest").firstOrNull() ?: return null to null
        val items = manifest.getElementsByTag("item")

        val e3 = items.firstOrNull { it.attr("properties").split(' ').contains("cover-image") }
        val e3Href = e3?.attr("href")?.ifEmpty { null }
        if (e3Href != null) {
            val bytes = lookup(resolve(opfDir, e3Href))
            if (bytes != null) return bytes to extensionOf(e3Href)
        }

        val metaCover = opf.getElementsByTag("metadata").firstOrNull()?.getElementsByTag("meta")
            ?.firstOrNull { it.attr("name") == "cover" }
            ?.attr("content")?.ifEmpty { null }
        if (metaCover != null) {
            val item = items.firstOrNull { it.attr("id") == metaCover }
            val href = item?.attr("href")?.ifEmpty { null }
            if (href != null) {
                val bytes = lookup(resolve(opfDir, href))
                if (bytes != null) return bytes to extensionOf(href)
            }
        }
        return null to null
    }

    private fun parseYear(raw: String): String? {
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

    private fun rootfilePath(containerXml: ByteArray): String? {
        val doc = parseXml(containerXml) ?: return null
        return doc.getElementsByTag("rootfile").firstOrNull()?.attr("full-path")?.takeIf { it.isNotEmpty() }
    }

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

    private fun parseXml(bytes: ByteArray): Element? = try {
        Ksoup.parse(html = bytes.decodeToString(), parser = Parser.xmlParser()).also { it }
    } catch (_: Exception) {
        null
    }

    private fun Element.dcElementsByTag(local: String): List<Element> =
        getElementsByTag("dc:$local").toList() + getElementsByTag(local).toList()

    private fun Element.firstDcText(local: String): String? =
        dcElementsByTag(local).firstOrNull()?.text()?.trim()?.ifEmpty { null }

    private fun Element.collectDcText(local: String): List<String> =
        dcElementsByTag(local).mapNotNull { it.text()?.trim()?.ifEmpty { null } }
}
