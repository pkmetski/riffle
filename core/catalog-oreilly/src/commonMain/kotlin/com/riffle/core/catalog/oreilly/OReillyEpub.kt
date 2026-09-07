package com.riffle.core.catalog.oreilly

/**
 * Pure helpers that shape scraped O'Reilly chapter HTML into EPUB-ready XHTML. No network, no I/O —
 * unit-testable. [OReillyCatalog] fetches the raw bytes and calls these to build the
 * [com.riffle.core.catalog.oreilly.epub.SynthesizedBook].
 */
internal object OReillyEpub {

    fun chapterFileName(index: Int): String = "chapter$index.xhtml"

    fun chapterId(index: Int): String = "ch$index"

    /**
     * Wrap a chapter's body HTML into a standalone XHTML document with the given stylesheet links.
     * O'Reilly's chapter `content` endpoint returns either a full document or a fragment; we extract
     * the `<body>` inner content when a document is present so we control the `<head>` (charset +
     * stylesheet links) and guarantee a single well-formed XHTML doc.
     */
    fun wrapChapter(title: String, rawHtml: String, cssHrefs: List<String>): String {
        val body = selfCloseVoidElements(extractBodyInner(rawHtml))
        val links = cssHrefs.joinToString("\n  ") {
            """<link rel="stylesheet" type="text/css" href="${it.xmlAttr()}"/>"""
        }
        return buildString {
            append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
            // O'Reilly chapter bodies use epub:type attributes (e.g. pagebreak) — declare the ops
            // namespace or the EPUB XML parser rejects the undeclared `epub:` prefix.
            append("<html xmlns=\"http://www.w3.org/1999/xhtml\" xmlns:epub=\"http://www.idpf.org/2007/ops\">\n<head>\n")
            append("  <meta charset=\"UTF-8\"/>\n")
            append("  <title>${title.xmlText()}</title>\n")
            if (links.isNotEmpty()) append("  ").append(links).append("\n")
            append("</head>\n<body>\n")
            append(body)
            append("\n</body>\n</html>\n")
        }
    }

    // HTML void elements — serialized without a closing slash in O'Reilly's content, which breaks
    // the strict XML parser Readium uses. Self-close them so the fragment is well-formed XHTML.
    private val VOID_ELEMENTS =
        listOf("area", "base", "br", "col", "embed", "hr", "img", "input", "link", "meta", "param", "source", "track", "wbr")
    private val voidElementRegex =
        Regex("<(${VOID_ELEMENTS.joinToString("|")})\\b([^>]*?)/?>", RegexOption.IGNORE_CASE)

    /** Rewrite `<img …>` / `<br>` etc. to `<img …/>` / `<br/>` so the XHTML parses as XML. */
    internal fun selfCloseVoidElements(html: String): String =
        voidElementRegex.replace(html) { m -> "<${m.groupValues[1]}${m.groupValues[2]}/>" }

    /** Returns the inner HTML of the first `<body>…</body>`, or the whole string if none is present. */
    internal fun extractBodyInner(html: String): String {
        val open = html.indexOf("<body", ignoreCase = true)
        if (open < 0) return html
        val openEnd = html.indexOf('>', open)
        if (openEnd < 0) return html
        val close = html.lastIndexOf("</body>", ignoreCase = true)
        if (close < 0 || close < openEnd) return html.substring(openEnd + 1)
        return html.substring(openEnd + 1, close)
    }

    /**
     * The relative prefix that walks from a chapter packaged at [chapterFullPath] back to the OEBPS
     * root — one `../` per subdirectory. A chapter at `xhtml/cover.xhtml` yields `../` so a root-level
     * asset `images/x.jpg` is reached as `../images/x.jpg`; a root-level chapter yields `""`.
     */
    fun relPrefixFor(chapterFullPath: String): String =
        "../".repeat(chapterFullPath.count { it == '/' })

    /** Package [assetFullPath] as a link/href relative to a chapter at [chapterFullPath]. */
    fun relativeTo(chapterFullPath: String, assetFullPath: String): String =
        relPrefixFor(chapterFullPath) + assetFullPath

    /** Local packaged path for a remote asset URL, keyed by index to avoid collisions. */
    fun localImagePath(url: String, index: Int): String {
        val ext = url.substringBefore('?').substringAfterLast('.', "").lowercase()
            .takeIf { it.length in 1..5 && it.all { c -> c.isLetterOrDigit() } } ?: "img"
        return "images/img$index.$ext"
    }

    /** Rewrite absolute asset URLs in [html] to their packaged relative paths. */
    fun rewriteAssetUrls(html: String, urlToLocal: Map<String, String>): String {
        var out = html
        for ((remote, local) in urlToLocal) {
            out = out.replace(remote, local)
        }
        return out
    }

    private fun String.xmlText(): String =
        replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    private fun String.xmlAttr(): String =
        xmlText().replace("\"", "&quot;")
}
