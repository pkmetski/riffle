package com.riffle.app.feature.reader

import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

// Detects footnote-style link targets so the reader can show a popup even when
// Readium's strict EPUB3 noteref classification doesn't fire. Real-world EPUBs
// frequently mark only the first link with epub:type="noteref" while the
// targets sit in a <section epub:type="footnotes"> or carry class="footnote".
internal object FootnoteResolver {

    private val FOOTNOTE_EPUB_TYPES = setOf(
        "footnote", "endnote", "rearnote",
        "footnotes", "endnotes", "rearnotes",
        "doc-footnote", "doc-endnote",
    )

    private val FOOTNOTE_ROLES = setOf(
        "doc-footnote", "doc-endnote",
    )

    private val FOOTNOTE_CLASSES = setOf(
        "footnote", "endnote", "rearnote",
        "footnotes", "endnotes", "rearnotes",
    )

    fun parse(html: String): Document = Jsoup.parse(html)

    // Returns the footnote body text for [targetId] within [doc], or null if
    // the element with that id does not look like a footnote.
    //
    // Implementation note: uses getElementById, NOT Jsoup CSS selectors. EPUB
    // footnote IDs frequently contain a dot (O'Reilly's `ftn.ch01fn01` is the
    // canonical example). Jsoup's `select("#ftn.ch01fn01")` parses that as
    // "id=ftn AND class=ch01fn01" and matches nothing — exactly the Readium
    // 3.0.0 bug this whole feature works around. Do not swap getElementById
    // for select here.
    fun extractFootnoteText(doc: Document, targetId: String): String? {
        val element = doc.getElementById(targetId) ?: return null
        if (!looksLikeFootnote(element)) return null
        return element.text().trim().takeIf { it.isNotEmpty() }
    }

    // Resolves an anchor tap from the WebView to footnote popup text. Returns
    // null when no popup should be shown (cache cold, no current spine,
    // target is a regular cross-reference, etc.).
    fun resolveAnchorTap(
        currentHref: String?,
        cache: Map<String, Document>,
        fragmentId: String,
    ): String? {
        val href = currentHref ?: return null
        val key = href.substringBefore('#')
        val doc = cache[key] ?: return null
        return extractFootnoteText(doc, fragmentId)
    }

    private fun looksLikeFootnote(element: Element): Boolean {
        var node: Element? = element
        while (node != null) {
            if (matchesAny(node.attr("epub:type"), FOOTNOTE_EPUB_TYPES)) return true
            if (matchesAny(node.attr("role"), FOOTNOTE_ROLES)) return true
            if (node.classNames().any { it.lowercase() in FOOTNOTE_CLASSES }) return true
            node = node.parent()
        }
        return false
    }

    private fun matchesAny(attr: String, allowed: Set<String>): Boolean {
        if (attr.isEmpty()) return false
        return attr.lowercase().split(Regex("\\s+")).any { it in allowed }
    }
}
