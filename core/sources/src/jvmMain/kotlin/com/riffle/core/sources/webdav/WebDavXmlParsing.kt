package com.riffle.core.sources.webdav

import org.xml.sax.Attributes
import org.xml.sax.helpers.DefaultHandler
import javax.xml.parsers.SAXParserFactory

/** Parse a WebDAV PROPFIND (207) response body and return the last path segment of each href. */
internal fun parsePropfindFilenames(xml: String): List<String> {
    if (xml.isBlank()) return emptyList()
    val handler = HrefCollector()
    return try {
        SAXParserFactory.newInstance().apply { isNamespaceAware = true }
            .newSAXParser()
            .parse(xml.byteInputStream(Charsets.UTF_8), handler)
        handler.hrefs
            .map { it.substringAfterLast('/') }
            .filter { it.isNotEmpty() }
            .filter { !it.startsWith("._") }
    } catch (_: Exception) {
        emptyList()
    }
}

internal class HrefCollector : DefaultHandler() {
    val hrefs = mutableListOf<String>()
    private val current = StringBuilder()
    private var inHref = false

    override fun startElement(uri: String?, localName: String?, qName: String?, attributes: Attributes?) {
        if (localName == "href") { inHref = true; current.setLength(0) }
    }

    override fun characters(ch: CharArray?, start: Int, length: Int) {
        if (inHref && ch != null) current.appendRange(ch, start, start + length)
    }

    override fun endElement(uri: String?, localName: String?, qName: String?) {
        if (localName == "href") { hrefs.add(current.toString().trim()); inHref = false }
    }
}
