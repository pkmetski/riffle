package com.riffle.app.feature.reader

import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode

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
        // Target is a footnote-style element: show the popup, [content] is its body.
        data class Footnote(val content: FootnoteContent) : AnchorTarget

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

    // Bare-text URLs to linkify. Stops at whitespace, angle brackets, and parens
    // so wrapping punctuation isn't swallowed into the link.
    private val BARE_URL_REGEX = Regex("""https?://[^\s<>()]+""", RegexOption.IGNORE_CASE)

    // Trailing characters trimmed off a detected bare URL — sentence punctuation
    // that commonly abuts a URL in prose but isn't part of it.
    private const val URL_TRAILING_PUNCTUATION = ".,;:!?\"')]}>"

    // Block-level tags that imply a separating space between their content and
    // adjacent content when flattened to plain text.
    private val BLOCK_TAGS = setOf(
        "p", "div", "section", "aside", "article", "blockquote", "pre",
        "ul", "ol", "li", "dl", "dt", "dd",
        "table", "thead", "tbody", "tr", "td", "th",
        "figure", "figcaption", "h1", "h2", "h3", "h4", "h5", "h6", "hr",
    )

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
    fun extractFootnoteContent(doc: Document, targetId: String): FootnoteContent? {
        val target = doc.getElementById(targetId) ?: return null
        if (!looksLikeFootnote(target)) return null
        // Some EPUBs (e.g. "Influence Without Authority") point the in-text
        // reference at the note's inner back-reference anchor — an <a> whose
        // text is just the marker ("1.") — rather than the note entry itself.
        // Landing there yields a popup that shows only the number, so climb to
        // the enclosing note entry before reading the body. When the marker has
        // no note-entry ancestor it isn't note content at all but an in-prose
        // noteref (Lean Customer Development's <a class="footnote"> markers carry
        // the signal themselves yet sit in body text): bail so the reader treats
        // the tap as a backlink and navigates to the reference instead of
        // showing a "[4]"-only popup.
        val block = if (isBackReference(target)) noteEntryFor(target) ?: return null else target
        return noteContent(block).takeIf { it.text.isNotEmpty() }
    }

    // Builds footnote content from a raw note HTML fragment, used by the Readium
    // shouldFollowInternalLink fallback path (which hands us the note's markup
    // directly rather than a cached document + id). Same marker/link processing
    // as the getElementById path. Returns null when the body is empty.
    fun footnoteContent(noteHtml: String): FootnoteContent? =
        noteContent(Jsoup.parse(noteHtml).body()).takeIf { it.text.isNotEmpty() }

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
        val footnote = extractFootnoteContent(doc, fragmentId)
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
    ): FootnoteContent? =
        (classifyAnchorTap(currentHref, cache, fragmentId) as? AnchorTarget.Footnote)?.content

    // The visible note body for [block] with its links resolved. Cloning keeps
    // the cached document untouched. See the helpers below for the transform:
    // external links stay clickable, marker numbers are preserved as plain text,
    // pure chrome (return arrows, non-numeric backlinks) is removed, and bare URLs
    // are linkified.
    private fun noteContent(block: Element): FootnoteContent {
        val clone = block.clone()
        rewriteAnchors(clone)
        val builder = InlineTextBuilder()
        appendInline(clone, builder)
        linkifyBareUrls(builder.text, builder.links)
        return FootnoteContent(builder.text, builder.links.toList())
    }

    // Classifies every anchor and rewrites the tree in place: external links are
    // left for appendInline to capture, pure chrome is removed, and everything
    // else (numeric markers, in-document cross-references) is unwrapped to plain
    // text since a popup can't navigate it.
    private fun rewriteAnchors(root: Element) {
        root.select("a").forEach { a ->
            when {
                isExternalLink(a) -> Unit
                isBackReferenceChrome(a) -> a.remove()
                else -> a.unwrap()
            }
        }
    }

    private fun isExternalLink(a: Element): Boolean {
        val href = a.attr("href").lowercase()
        return href.startsWith("http://") ||
            href.startsWith("https://") ||
            href.startsWith("mailto:")
    }

    // A back-reference that carries no information: a return-to-text glyph or a
    // typed backlink whose text isn't a number. Numeric markers are explicitly
    // excluded so their digit survives as plain text.
    private fun isBackReferenceChrome(a: Element): Boolean {
        if (MARKER_REGEX.matches(a.text().trim())) return false
        return isTypedOrGlyphBacklink(a)
    }

    // The signal-based backlink checks shared by [isBackReference] and
    // [isBackReferenceChrome]: a DPUB-ARIA/EPUB3 backlink type/role, or a
    // return-to-text glyph. A new backlink signal only needs adding here.
    private fun isTypedOrGlyphBacklink(a: Element): Boolean {
        if (matchesAny(a.attr("epub:type"), BACKLINK_EPUB_TYPES)) return true
        if (matchesAny(a.attr("role"), BACKLINK_ROLES)) return true
        return a.text().trim() in BACKLINK_GLYPHS
    }

    // Walks the cleaned tree in document order, accumulating visible text and
    // recording a link span for each surviving external anchor. Block elements
    // and <br> introduce a separating space (Jsoup's .text() did the same), so a
    // multi-paragraph note doesn't jam its blocks together; inline elements add
    // no spacing of their own.
    private fun appendInline(node: Node, builder: InlineTextBuilder) {
        for (child in node.childNodes()) {
            when {
                child is TextNode -> builder.append(child.wholeText)
                child is Element && isExternalLink(child) -> {
                    val range = builder.append(child.text())
                    if (range != null) {
                        builder.links.add(FootnoteLink(range.first, range.second, child.attr("href")))
                    }
                }
                child is Element && child.tagName().equals("br", ignoreCase = true) ->
                    builder.markBoundary()
                child is Element && child.tagName().lowercase() in BLOCK_TAGS -> {
                    builder.markBoundary()
                    appendInline(child, builder)
                    builder.markBoundary()
                }
                child is Element -> appendInline(child, builder)
            }
        }
    }

    // Bare-text URLs left in the assembled body become links too, unless they
    // already sit inside an anchor span. Trailing sentence punctuation is trimmed
    // so "see http://x." doesn't linkify the period.
    private fun linkifyBareUrls(text: String, links: MutableList<FootnoteLink>) {
        for (match in BARE_URL_REGEX.findAll(text)) {
            val start = match.range.first
            var end = match.range.last + 1
            while (end > start && text[end - 1] in URL_TRAILING_PUNCTUATION) end--
            if (end <= start) continue
            if (links.any { it.start < end && start < it.end }) continue
            links.add(FootnoteLink(start, end, text.substring(start, end)))
        }
    }

    // Accumulates text with whitespace collapsed to single spaces, never leading
    // or trailing, so recorded link offsets line up exactly with the final text.
    private class InlineTextBuilder {
        private val sb = StringBuilder()
        val links = mutableListOf<FootnoteLink>()
        private var pendingSpace = false

        val text: String get() = sb.toString()

        // Records a whitespace boundary (a single space before the next visible
        // character), unless we're at the very start. Used at block-element edges.
        fun markBoundary() {
            if (sb.isNotEmpty()) pendingSpace = true
        }

        // Appends [raw], returning the [start, end) range (end exclusive) of the
        // visible characters written, or null if [raw] was all whitespace.
        fun append(raw: String): Pair<Int, Int>? {
            var first = -1
            var last = -1
            for (c in raw) {
                if (c.isWhitespace()) {
                    if (sb.isNotEmpty()) pendingSpace = true
                } else {
                    if (pendingSpace) {
                        sb.append(' ')
                        pendingSpace = false
                    }
                    if (first < 0) first = sb.length
                    sb.append(c)
                    last = sb.length
                }
            }
            return if (first < 0) null else first to last
        }
    }

    // True when [element] is an in-document anchor that is navigation chrome
    // rather than note content: the marker that pairs with the in-text
    // superscript ("7."/"[7]"), a "return to text" arrow (↑), or an anchor
    // carrying the EPUB3/DPUB-ARIA backlink type/role.
    private fun isBackReference(element: Element): Boolean {
        if (!element.tagName().equals("a", ignoreCase = true)) return false
        if (!element.attr("href").startsWith("#")) return false
        if (isTypedOrGlyphBacklink(element)) return true
        return MARKER_REGEX.matches(element.text().trim())
    }

    // Climbs from a marker anchor to the enclosing note entry: the nearest
    // ancestor that itself carries a footnote signal (so a single <aside>, not
    // the plural <section epub:type="rearnotes"> that wraps every note). Returns
    // null when no ancestor carries a footnote signal — the marker is then an
    // in-prose noteref/backlink, not the inner marker of a note entry, and has
    // no body to show.
    private fun noteEntryFor(marker: Element): Element? {
        var node: Element? = marker.parent()
        while (node != null) {
            if (carriesFootnoteSignal(node)) return node
            node = node.parent()
        }
        return null
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
