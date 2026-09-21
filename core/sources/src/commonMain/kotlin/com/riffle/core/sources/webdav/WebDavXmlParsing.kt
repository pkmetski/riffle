package com.riffle.core.sources.webdav

import com.fleeksoft.ksoup.Ksoup
import com.fleeksoft.ksoup.parser.Parser

/**
 * Parse a WebDAV PROPFIND (207) response body and return the last path segment of each href.
 *
 * ksoup rather than `javax.xml` SAX: this is the one piece of the WebDAV client that was truly
 * JVM-only, and it is why `core:sources`' whole webdav package lived in `jvmMain` and iOS had no
 * annotation sync and no cross-device progress at all. ksoup's XML parser is the same one
 * `core:catalog-chitanka` moved onto for exactly this reason.
 *
 * Namespace handling matches the SAX version's `isNamespaceAware = true` + `localName == "href"`:
 * ksoup keeps the prefix in the tag name, so the selector matches both `<d:href>` and a
 * default-namespaced `<href>`, and is case-insensitive the way XML element matching here needs to
 * be tolerant of the (non-conforming but common) `<D:HREF>`.
 */
internal fun parsePropfindFilenames(xml: String): List<String> {
    if (xml.isBlank()) return emptyList()
    return try {
        val document = Ksoup.parse(html = xml, parser = Parser.xmlParser())
        document.select("*")
            .filter { it.tagName().substringAfter(':').equals("href", ignoreCase = true) }
            .map { it.text().trim() }
            .map { it.substringAfterLast('/') }
            .filter { it.isNotEmpty() }
            // "._Foo" is an AppleDouble resource fork that macOS writes beside every file it
            // copies to a WebDAV share. Never a Riffle file.
            .filter { !it.startsWith("._") }
    } catch (_: Exception) {
        emptyList()
    }
}
