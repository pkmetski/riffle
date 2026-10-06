package com.riffle.feature.reader.highlights

/**
 * Builds the OPF, nav, and container.xml XML documents for the synthetic on-disk EPUB that
 * iOS opens via Readium-Swift. Android uses an in-memory [Publication] instead (no disk write),
 * but the XHTML per chapter is byte-identical — produced by [renderChapterHtml].
 *
 * Directory layout produced by [ElidedEpubPackager]:
 * ```
 * <root>/
 *   META-INF/container.xml
 *   content.opf
 *   nav.xhtml
 *   highlights/
 *     ch0.xhtml
 *     ch1.xhtml
 *     ...
 * ```
 */
object ElidedEpubPackager {

    /** Relative path from the epub root to a given spine chapter. */
    fun chapterHref(index: Int): String = "highlights/ch$index.xhtml"

    /** content.opf XML string for [chapters]. */
    fun buildOpf(
        bookTitle: String?,
        itemId: String,
        chapters: List<ChapterElision>,
    ): String {
        val safeTitle = (bookTitle ?: "Annotations").xmlEscapeAttr()
        val manifestItems = chapters.mapIndexed { i, _ ->
            val href = chapterHref(i)
            """    <item id="ch$i" href="$href" media-type="application/xhtml+xml"/>"""
        }.joinToString("\n")
        val spineItems = chapters.indices.joinToString("\n") { i ->
            """    <itemref idref="ch$i"/>"""
        }
        val safeId = itemId.xmlEscapeAttr()
        return """<?xml version="1.0" encoding="UTF-8"?>
<package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="uid">
  <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
    <dc:identifier id="uid">$safeId</dc:identifier>
    <dc:title>$safeTitle</dc:title>
    <dc:language>en</dc:language>
    <meta property="dcterms:modified">2020-01-01T00:00:00Z</meta>
  </metadata>
  <manifest>
    <item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/>
$manifestItems
  </manifest>
  <spine>
$spineItems
  </spine>
</package>"""
    }

    /** nav.xhtml TOC for [chapters]. */
    fun buildNav(bookTitle: String?, chapters: List<ChapterElision>): String {
        val safeTitle = (bookTitle ?: "Annotations").xmlEscape()
        val navItems = chapters.mapIndexed { i, ch ->
            val href = chapterHref(i)
            val chTitle = ch.title.xmlEscape()
            """      <li><a href="$href">$chTitle</a></li>"""
        }.joinToString("\n")
        return """<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE html>
<html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops">
<head><title>$safeTitle</title></head>
<body>
  <nav epub:type="toc">
    <ol>
$navItems
    </ol>
  </nav>
</body>
</html>"""
    }

    /** META-INF/container.xml pointing at content.opf. */
    fun buildContainerXml(): String = """<?xml version="1.0" encoding="UTF-8"?>
<container xmlns="urn:oasis:names:tc:opendocument:xmlns:container" version="1.0">
  <rootfiles>
    <rootfile full-path="content.opf" media-type="application/oebps-package+xml"/>
  </rootfiles>
</container>"""

    /** MIME type file required by EPUB 3 spec (written at the root of the epub dir). */
    const val MIME_TYPE_CONTENT = "application/epub+zip"

    private fun String.xmlEscape(): String =
        replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    private fun String.xmlEscapeAttr(): String =
        replace("&", "&amp;").replace("\"", "&quot;")
}
