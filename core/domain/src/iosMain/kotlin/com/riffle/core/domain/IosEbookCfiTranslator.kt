package com.riffle.core.domain

import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.Foundation.NSData
import platform.Foundation.dataWithContentsOfFile

/**
 * iOS [EbookCfiTranslator] backed by a cached EPUB file on disk (ADR 0013). Opens the ZIP
 * per-operation via [IosZipArchive]; parsed spine and chapter HTML are cached in memory so
 * repeated calls against the same instance need no further I/O — mirrors the JVM
 * EbookCfiTranslatorImpl's caching shape.
 */
class IosEbookCfiTranslator(private val epubFilePath: String) : EbookCfiTranslator {

    private val archive: IosZipArchive? by lazy { readArchive() }
    private var spineHrefs: List<String>? = null
    private val htmlCache = mutableMapOf<Int, String>()

    override suspend fun cfiToLocatorJson(epubcfi: String): String? {
        if (!epubcfi.startsWith("epubcfi(")) return null
        val spineIndex = epubCfiToSpineIndex(epubcfi) ?: return null
        val hrefs = ensureSpine() ?: return null
        val href = hrefs.getOrNull(spineIndex) ?: return null
        val html = readChapterHtml(hrefs, spineIndex) ?: return null
        val docPath = iosExtractCfiDocPath(epubcfi) ?: return null
        val progression = iosCfiDocPathToProgression(docPath, html) ?: return null
        val escapedHref = href.jsonEscaped()
        return """{"href":"$escapedHref","type":"application/xhtml+xml","locations":{"progression":$progression}}"""
    }

    override suspend fun locatorJsonToCfi(locatorJson: String): String? {
        if (locatorJson.startsWith("epubcfi(")) return locatorJson
        val href = extractJsonStringField(locatorJson, "href") ?: return null
        val progression = extractJsonProgression(locatorJson) ?: return null
        val hrefs = ensureSpine() ?: return null
        val spineIndex = hrefs.indexOfFirst { normalizeEpubHref(it) == normalizeEpubHref(href) }
        if (spineIndex < 0) return null
        val html = readChapterHtml(hrefs, spineIndex) ?: return null
        val docPath = iosProgressionToCfiDocPath(progression, html) ?: return null
        return "epubcfi(/6/${(spineIndex + 1) * 2}!$docPath)"
    }

    private fun ensureSpine(): List<String>? {
        spineHrefs?.let { return it }
        val a = archive ?: return null
        return parseSpineHrefs(a)?.also { spineHrefs = it }
    }

    private fun readChapterHtml(hrefs: List<String>, spineIndex: Int): String? {
        htmlCache[spineIndex]?.let { return it }
        val a = archive ?: return null
        val entryPath = normalizeEpubHref(hrefs[spineIndex])
        return a.readEntryAsText(entryPath)?.also { htmlCache[spineIndex] = it }
    }

    @OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
    private fun readArchive(): IosZipArchive? {
        val data = NSData.dataWithContentsOfFile(epubFilePath) ?: return null
        val bytes = ByteArray(data.length.toInt())
        if (bytes.isNotEmpty()) {
            bytes.usePinnedWrite(data)
        }
        return IosZipArchive(bytes)
    }

    /** Returns hrefs of the EPUB's spine, relative to the EPUB root, by parsing container.xml → OPF → spine. */
    private fun parseSpineHrefs(archive: IosZipArchive): List<String>? {
        val containerXml = archive.readEntryAsText("META-INF/container.xml") ?: return null
        val rootfilePath = extractXmlAttribute(containerXml, "rootfile", "full-path") ?: return null

        val opfXml = archive.readEntryAsText(rootfilePath) ?: return null
        val opfDir = rootfilePath.substringBeforeLast('/', "")

        val manifest = parseManifest(opfXml)
        return parseSpineItemrefs(opfXml).mapNotNull { idref ->
            val rawHref = manifest[idref] ?: return@mapNotNull null
            if (opfDir.isEmpty()) rawHref else "$opfDir/$rawHref"
        }
    }
}

@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
private fun ByteArray.usePinnedWrite(data: NSData) {
    this.usePinned { pinned ->
        platform.posix.memcpy(pinned.addressOf(0), data.bytes, data.length)
    }
}

private fun String.jsonEscaped(): String =
    replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r")

/** Minimal `{"href":"..."}` string-field reader — the locator JSON is always our own serialisation. */
private fun extractJsonStringField(json: String, field: String): String? {
    val regex = Regex(""""$field"\s*:\s*"((?:[^"\\]|\\.)*)"""")
    val match = regex.find(json) ?: return null
    return match.groupValues[1].replace("\\\"", "\"").replace("\\\\", "\\")
}

private fun extractJsonProgression(json: String): Double? {
    val regex = Regex(""""progression"\s*:\s*([0-9.eE+-]+)""")
    return regex.find(json)?.groupValues?.get(1)?.toDoubleOrNull()
}

/** Minimal XML attribute reader for a single self/simple element, e.g. `<rootfile full-path="..."/>`. */
private fun extractXmlAttribute(xml: String, tag: String, attribute: String): String? {
    val tagRegex = Regex("""<$tag\b[^>]*>""")
    val tagMatch = tagRegex.find(xml) ?: return null
    val attrRegex = Regex("""$attribute\s*=\s*"([^"]*)"""")
    return attrRegex.find(tagMatch.value)?.groupValues?.get(1)
}

/** Parses OPF `<manifest><item id="..." href="..."/></manifest>` into id -> href. */
private fun parseManifest(opfXml: String): Map<String, String> {
    val manifestSection = Regex("""<manifest\b[^>]*>(.*?)</manifest>""", RegexOption.DOT_MATCHES_ALL)
        .find(opfXml)?.groupValues?.get(1) ?: return emptyMap()
    val itemRegex = Regex("""<item\b([^>]*)/?>""")
    val result = mutableMapOf<String, String>()
    for (match in itemRegex.findAll(manifestSection)) {
        val attrs = match.groupValues[1]
        val id = Regex("""\bid\s*=\s*"([^"]*)"""").find(attrs)?.groupValues?.get(1) ?: continue
        val href = Regex("""\bhref\s*=\s*"([^"]*)"""").find(attrs)?.groupValues?.get(1) ?: continue
        result[id] = href
    }
    return result
}

/** Parses OPF `<spine><itemref idref="..."/></spine>` into an ordered list of idrefs. */
private fun parseSpineItemrefs(opfXml: String): List<String> {
    val spineSection = Regex("""<spine\b[^>]*>(.*?)</spine>""", RegexOption.DOT_MATCHES_ALL)
        .find(opfXml)?.groupValues?.get(1) ?: return emptyList()
    val itemrefRegex = Regex("""<itemref\b([^>]*)/?>""")
    return itemrefRegex.findAll(spineSection).mapNotNull { match ->
        Regex("""\bidref\s*=\s*"([^"]*)"""").find(match.groupValues[1])?.groupValues?.get(1)
    }.toList()
}
