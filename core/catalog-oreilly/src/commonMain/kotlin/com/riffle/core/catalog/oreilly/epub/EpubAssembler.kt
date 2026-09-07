package com.riffle.core.catalog.oreilly.epub

/** A resource (stylesheet, image, font) packaged into the synthesized EPUB, path relative to OEBPS/. */
data class EpubResource(val relativePath: String, val bytes: ByteArray, val mediaType: String) {
    override fun equals(other: Any?): Boolean =
        this === other || (other is EpubResource && relativePath == other.relativePath &&
            mediaType == other.mediaType && bytes.contentEquals(other.bytes))

    override fun hashCode(): Int = 31 * (31 * relativePath.hashCode() + mediaType.hashCode()) + bytes.contentHashCode()
}

/** One spine document, XHTML already normalized, path relative to OEBPS/. */
data class EpubChapter(
    val id: String,
    val relativePath: String,
    val title: String,
    val xhtml: String,
)

/** Everything needed to emit one EPUB — the scraper populates this, the assembler serializes it. */
data class SynthesizedBook(
    val identifier: String,
    val title: String,
    val authors: List<String>,
    val language: String = "en",
    val publisher: String? = null,
    val chapters: List<EpubChapter>,
    val resources: List<EpubResource> = emptyList(),
    /** Cover image resource (must also be present in [resources]); null when the book has no cover. */
    val coverPath: String? = null,
)

/**
 * Serializes a [SynthesizedBook] into EPUB 3 bytes via [EpubZipWriter]. All output is deterministic
 * (no timestamps) so identical input yields identical bytes — helpful for caching and tests.
 *
 * Layout: `mimetype` (STORED, first) · `META-INF/container.xml` · `OEBPS/content.opf` ·
 * `OEBPS/nav.xhtml` · one XHTML per chapter · packaged resources.
 */
object EpubAssembler {

    private const val OEBPS = "OEBPS"
    private const val OPF_PATH = "$OEBPS/content.opf"
    private const val NAV_ID = "nav"
    private const val NAV_PATH = "$OEBPS/nav.xhtml"

    fun assemble(book: SynthesizedBook): ByteArray {
        val entries = ArrayList<EpubZipEntry>()

        // 1. mimetype MUST be first and STORED.
        entries += EpubZipEntry("mimetype", "application/epub+zip".encodeToByteArray())

        // 2. Container.
        entries += EpubZipEntry("META-INF/container.xml", containerXml().encodeToByteArray())

        // 3. Package document.
        entries += EpubZipEntry(OPF_PATH, contentOpf(book).encodeToByteArray())

        // 4. EPUB 3 navigation document.
        entries += EpubZipEntry(NAV_PATH, navXhtml(book).encodeToByteArray())

        // 5. Chapters.
        for (ch in book.chapters) {
            entries += EpubZipEntry("$OEBPS/${ch.relativePath}", ch.xhtml.encodeToByteArray())
        }

        // 6. Resources (css, images, cover).
        for (res in book.resources) {
            entries += EpubZipEntry("$OEBPS/${res.relativePath}", res.bytes)
        }

        return EpubZipWriter.write(entries)
    }

    private fun containerXml(): String = """
        <?xml version="1.0" encoding="UTF-8"?>
        <container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
          <rootfiles>
            <rootfile full-path="$OPF_PATH" media-type="application/oebps-package+xml"/>
          </rootfiles>
        </container>
    """.trimIndent().trim()

    private fun contentOpf(book: SynthesizedBook): String {
        val creators = book.authors.mapIndexed { i, a ->
            """<dc:creator id="creator$i">${a.xmlEscape()}</dc:creator>"""
        }.joinToString("\n    ")
        val publisher = book.publisher?.let { "\n    <dc:publisher>${it.xmlEscape()}</dc:publisher>" } ?: ""
        val coverMeta = book.coverPath?.let {
            "\n    <meta name=\"cover\" content=\"${manifestId(it)}\"/>"
        } ?: ""

        val manifestItems = buildList {
            add("""<item id="$NAV_ID" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/>""")
            for (ch in book.chapters) {
                add("""<item id="${ch.id}" href="${ch.relativePath.xmlEscape()}" media-type="application/xhtml+xml"/>""")
            }
            for (res in book.resources) {
                val props = if (res.relativePath == book.coverPath) """ properties="cover-image"""" else ""
                add("""<item id="${manifestId(res.relativePath)}" href="${res.relativePath.xmlEscape()}" media-type="${res.mediaType}"$props/>""")
            }
        }.joinToString("\n    ")

        val spineItems = book.chapters.joinToString("\n    ") { """<itemref idref="${it.id}"/>""" }

        return """
            <?xml version="1.0" encoding="UTF-8"?>
            <package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="pub-id">
              <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
                <dc:identifier id="pub-id">${book.identifier.xmlEscape()}</dc:identifier>
                <dc:title>${book.title.xmlEscape()}</dc:title>
                <dc:language>${book.language.xmlEscape()}</dc:language>
                <meta property="dcterms:modified">2024-01-01T00:00:00Z</meta>
                $creators$publisher$coverMeta
              </metadata>
              <manifest>
                $manifestItems
              </manifest>
              <spine>
                $spineItems
              </spine>
            </package>
        """.trimIndent().trim()
    }

    private fun navXhtml(book: SynthesizedBook): String {
        val items = book.chapters.joinToString("\n        ") {
            """<li><a href="${it.relativePath.xmlEscape()}">${it.title.xmlEscape()}</a></li>"""
        }
        return """
            <?xml version="1.0" encoding="UTF-8"?>
            <html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops" lang="${book.language.xmlEscape()}">
            <head><title>${book.title.xmlEscape()}</title></head>
            <body>
              <nav epub:type="toc" id="toc">
                <h1>Contents</h1>
                <ol>
                    $items
                </ol>
              </nav>
            </body>
            </html>
        """.trimIndent().trim()
    }

    /** Stable manifest id for a resource path (chapters carry their own ids). */
    // A short hash of the full path disambiguates resources that sanitize to the same slug (e.g.
    // "images/a-b.jpg" vs "images/a_b.jpg") — a manifest with duplicate item ids is an invalid OPF.
    // Stable per path, so the cover <meta> and the <item> for the same path always agree.
    private fun manifestId(path: String): String =
        "res-" + path.map { if (it.isLetterOrDigit()) it else '_' }.joinToString("") +
            "-" + path.hashCode().toUInt().toString(16)

    private fun String.xmlEscape(): String = this
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&apos;")

}
