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

    // The outcome of classifying a tapped in-document anchor (href="#id").
    sealed interface AnchorTarget {
        // Target is a footnote-style element: show the popup, [text] is its body.
        data class Footnote(val text: String) : AnchorTarget

        // Target is a regular in-document element (e.g. a "Figure 4.1"
        // cross-reference). The caller must navigate via Readium's go() so the
        // viewport lands on a column-page boundary. Letting the WebView perform
        // its default same-document anchor scroll lands scrollLeft mid-column in
        // a paginated reflowable layout — the page sits split between two
        // columns and Readium never re-snaps it.
        data object CrossReference : AnchorTarget

        // Can't tell — cache cold, no current resource, or the id isn't in the
        // current spine doc. Leave it to the WebView's default handling.
        data object Unresolved : AnchorTarget
    }

    fun parse(html: String): Document = Jsoup.parse(html)

    // Anchors whose entire text is just a note marker — "7", "7.", "[7]",
    // "(7)", "7)" — are back-references into the body, not note content.
    private val MARKER_REGEX = Regex("""[\[(]?\d{1,4}[.)\]]?""")

    // "Return to text" glyphs an EPUB appends as a trailing back-link: upwards
    // arrow (↑), hooked/curved return arrows, carriage-return symbol.
    private val BACKLINK_GLYPHS = setOf(
        "↑", // ↑ upwards arrow
        "⤴", // ⤴ arrow pointing up then right
        "↩", // ↩ leftwards arrow with hook
        "↪", // ↪ rightwards arrow with hook
        "↰", // ↰ upwards arrow with tip leftwards
        "↵", // ↵ downwards arrow with corner leftwards
        "⏎", // ⏎ return symbol
        "⬆", // ⬆ heavy upwards arrow
    )

    // EPUB3 / DPUB-ARIA standard markers for the link that returns to the text.
    private val BACKLINK_EPUB_TYPES = setOf("backlink")
    private val BACKLINK_ROLES = setOf("doc-backlink")

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
        val target = doc.getElementById(targetId) ?: return null
        if (!looksLikeFootnote(target)) return null
        // Some EPUBs (e.g. "Influence Without Authority") point the in-text
        // reference at the note's inner back-reference anchor — an <a> whose
        // text is just the marker ("1.") — rather than the note entry itself.
        // Landing there yields a popup that shows only the number, so climb to
        // the enclosing note entry before reading the body.
        val block = if (isBackReference(target)) noteEntryFor(target) else target
        return noteText(block).takeIf { it.isNotEmpty() }
    }

    // Classifies a tapped in-document anchor against the cached spine doc so the
    // reader can decide between showing a footnote popup, navigating via
    // Readium's go() (snapped), or deferring to the WebView's default scroll.
    fun classifyAnchorTap(
        currentHref: String?,
        cache: Map<String, Document>,
        fragmentId: String,
    ): AnchorTarget {
        val href = currentHref ?: return AnchorTarget.Unresolved
        val doc = cache[href.substringBefore('#')] ?: return AnchorTarget.Unresolved
        // The id must exist in the current resource: the JS bridge only fires
        // for href="#id" links, which target the same document.
        doc.getElementById(fragmentId) ?: return AnchorTarget.Unresolved
        val footnote = extractFootnoteText(doc, fragmentId)
        return if (footnote != null) {
            AnchorTarget.Footnote(footnote)
        } else {
            AnchorTarget.CrossReference
        }
    }

    // Resolves an anchor tap from the WebView to footnote popup text. Returns
    // null when no popup should be shown (cache cold, no current spine,
    // target is a regular cross-reference, etc.).
    fun resolveAnchorTap(
        currentHref: String?,
        cache: Map<String, Document>,
        fragmentId: String,
    ): String? =
        (classifyAnchorTap(currentHref, cache, fragmentId) as? AnchorTarget.Footnote)?.text

    // The visible note body for [block], with any back-reference marker anchors
    // ("1.", "[7]" that link back into the text) removed — they're navigation
    // chrome, not content. Cloning keeps the cached document untouched.
    private fun noteText(block: Element): String {
        val clone = block.clone()
        clone.select("a[href^=#]").forEach { anchor ->
            if (isBackReference(anchor)) anchor.remove()
        }
        return clone.text().trim()
    }

    // True when [element] is an in-document anchor that is navigation chrome
    // rather than note content: the marker that pairs with the in-text
    // superscript ("7."/"[7]"), a "return to text" arrow (↑), or an anchor
    // carrying the EPUB3/DPUB-ARIA backlink type/role.
    private fun isBackReference(element: Element): Boolean {
        if (!element.tagName().equals("a", ignoreCase = true)) return false
        if (!element.attr("href").startsWith("#")) return false
        if (matchesAny(element.attr("epub:type"), BACKLINK_EPUB_TYPES)) return true
        if (matchesAny(element.attr("role"), BACKLINK_ROLES)) return true
        val text = element.text().trim()
        return MARKER_REGEX.matches(text) || text in BACKLINK_GLYPHS
    }

    // Climbs from a marker anchor to the enclosing note entry: the nearest
    // ancestor that itself carries a footnote signal (so a single <aside>, not
    // the plural <section epub:type="rearnotes"> that wraps every note).
    private fun noteEntryFor(marker: Element): Element {
        var node: Element? = marker.parent()
        while (node != null) {
            if (carriesFootnoteSignal(node)) return node
            node = node.parent()
        }
        return marker.parent() ?: marker
    }

    private fun looksLikeFootnote(element: Element): Boolean {
        var node: Element? = element
        while (node != null) {
            if (carriesFootnoteSignal(node)) return true
            node = node.parent()
        }
        return false
    }

    private fun carriesFootnoteSignal(node: Element): Boolean {
        if (matchesAny(node.attr("epub:type"), FOOTNOTE_EPUB_TYPES)) return true
        if (matchesAny(node.attr("role"), FOOTNOTE_ROLES)) return true
        if (node.classNames().any { it.lowercase() in FOOTNOTE_CLASSES }) return true
        return false
    }

    private fun matchesAny(attr: String, allowed: Set<String>): Boolean {
        if (attr.isEmpty()) return false
        return attr.lowercase().split(Regex("\\s+")).any { it in allowed }
    }
}
