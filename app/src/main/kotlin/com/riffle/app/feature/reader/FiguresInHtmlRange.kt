package com.riffle.app.feature.reader

import com.riffle.core.domain.EmbeddedFigure
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode

/**
 * Kotlin-side (jsoup-based) resolver: given a chapter's XHTML and the body-char range that a
 * highlight covers, returns the `<img>` / `<svg>` / `<picture>` / `<figure>` elements enclosed
 * by that range, as [EmbeddedFigure]s.
 *
 * This exists because the JS-side walker inside [SELECTION_SPAN_TRACKER_JS] silently missed
 * figures in paginated Readium: the tracker's `selectionchange` fires, the bridge is reachable
 * (rect popup positioning works), but the figures never made it into [SelectionFiguresStash] —
 * highlights spanning an equation image landed in the DB with empty `embeddedFigures` and no
 * border was drawn. Running the walk here on the already-loaded chapter HTML sidesteps every
 * WebView / range-selection / timing concern.
 *
 * Char range semantics match the CFI machinery (see `EpubCfiTranslator.countBodyChars` /
 * `findNodeAtChar`): blank-only text nodes are skipped; only readable text contributes to the
 * offset. A void element (like `<img>`) contributes zero characters; its "position" is the char
 * count of the readable text emitted before it in document order.
 */
internal fun findEnclosedFiguresInHtml(
    html: String,
    startChar: Long,
    endChar: Long,
): List<EmbeddedFigure> {
    if (startChar < 0 || endChar < startChar) return emptyList()
    val body = Jsoup.parse(html).body()
    val figures = mutableListOf<EmbeddedFigure>()
    val seen = HashSet<Element>()
    var order = 0
    var running = 0L

    fun visit(node: Node) {
        when (node) {
            is TextNode -> {
                if (node.wholeText.isNotBlank()) running += node.wholeText.length
            }
            is Element -> {
                val tag = node.tagName().lowercase()
                val isFigureTag = tag == "img" || tag == "svg" || tag == "picture" || tag == "figure"
                val elemStart = running
                node.childNodes().forEach { visit(it) }
                val elemEnd = running
                if (!isFigureTag) return
                // A figure is "enclosed" ONLY when the highlight STRADDLES it — the range must
                // include text-stream chars on BOTH sides of the figure (before AND after). A
                // range that ends exactly AT the figure's position (endChar == elemStart) does
                // NOT cover the figure: visually the highlight stops just before the figure,
                // and marking the figure as enclosed would give the user "text-before-only was
                // highlighted → figure got marked too". Symmetric for the leading edge.
                // For non-void figure-container tags (elemEnd > elemStart) we accept overlap of
                // the interior — a highlight that opens inside the figure's own text still
                // straddles it.
                val overlaps = if (elemStart == elemEnd) {
                    startChar < elemStart && elemStart < endChar
                } else {
                    startChar < elemEnd && elemStart < endChar
                }
                if (!overlaps) return
                val target: Element? = when (tag) {
                    "figure" -> node.selectFirst("img") ?: node.selectFirst("svg") ?: node.selectFirst("picture")
                    else -> node
                }
                if (target == null || !seen.add(target)) return
                val tt = target.tagName().lowercase()
                val caption = run {
                    val figParent = target.parents().firstOrNull { it.tagName().equals("figure", ignoreCase = true) }
                    val figCap = figParent?.selectFirst("figcaption")?.text()?.trim().orEmpty()
                    if (figCap.isNotEmpty()) figCap
                    else target.attr("alt").ifBlank { target.attr("aria-label") }.trim()
                }
                // Char offset relative to the highlight's start — where this figure sits in the
                // highlight's readable-text stream. Populated so the annotations panel and elided
                // reader can split the snippet at the true figure position (fix 2026-07-09).
                // Clamped to zero: a figure sitting BEFORE the highlight start (caught only via
                // the boundary-widening in the absorb-adjacent path) reports offset 0.
                val offsetInSnippet = (elemStart - startChar).coerceAtLeast(0L)
                figures += when (tt) {
                    "svg" -> EmbeddedFigure(
                        href = null,
                        svg = target.outerHtml(),
                        caption = caption,
                        order = order++,
                        charOffset = offsetInSnippet,
                    )
                    else -> {
                        val img = if (tt == "picture") target.selectFirst("img") else target
                        val src = img?.attr("src")?.trim()?.ifBlank { null }
                        EmbeddedFigure(
                            href = src,
                            svg = null,
                            caption = caption,
                            order = order++,
                            charOffset = offsetInSnippet,
                        )
                    }
                }
            }
            else -> Unit
        }
    }

    body.childNodes().forEach { visit(it) }
    return figures
}

/**
 * Locates a highlight's exact body-char range inside a chapter's XHTML by SEARCHING for the
 * snippet's text in the readable-body character stream, not by scaling a progression fraction.
 *
 * Why: `progression` — the fraction Readium hands back to createHighlight — has ~1% imprecision
 * (from the paginated CSS-column positioning model), so `startChar = progression * totalChars`
 * lands 40-60 body chars away from the actual snippet in the middle of a chapter. That drift is
 * enough to move the range's endpoint to just before an enclosed figure — the walker then reports
 * zero enclosed figures and the highlight lands without embeddedFigures (the exact bug that shipped
 * empty embeddedFigures for the "C = ΣCₚtₚ" equation in *A Philosophy of Software Design*).
 *
 * Searching for the snippet string against the readable-text stream gives an EXACT anchor. When
 * the search fails (snippet spans elements weirdly, or the HTML has been reformatted), falls back
 * to the progression-scaled range so the resolver stays best-effort. `textBefore` disambiguates
 * repeated snippets — earliest match after `textBefore` wins; when both are supplied, we anchor
 * on `textBefore + snippet` and drop the textBefore prefix. Returns the pair (startChar, endChar).
 */
internal fun anchorRangeToSnippet(
    html: String,
    snippet: String,
    textBefore: String,
    progression: Double,
): Pair<Long, Long> {
    val body = Jsoup.parse(html).body()
    // Build the body text stream the same way blank-aware char-counting does (matching
    // `EpubCfiTranslator.findNodeAtChar` semantics): concatenate non-blank text nodes verbatim.
    val bodyText = StringBuilder()
    fun visit(n: Node) {
        when (n) {
            is TextNode -> if (n.wholeText.isNotBlank()) bodyText.append(n.wholeText)
            is Element -> n.childNodes().forEach { visit(it) }
            else -> Unit
        }
    }
    body.childNodes().forEach { visit(it) }
    val stream = bodyText.toString()
    val totalChars = stream.length.toLong()

    // Try textBefore + snippet first — most robust anchor for repeated snippets. Text-node
    // boundaries collapse the "\n\n" between paragraphs to nothing in the stream, so strip such
    // markers from the search key too. Anchor keys use the FIRST paragraph of the snippet only:
    // a snippet that crosses an enclosed figure (like the equation image between "way:" and
    // "The overall…") has an artificial space in cleanSnippet where the void <img> lives, but
    // the body-text stream — built by concatenating non-blank text nodes verbatim — has no space
    // there ("way:The overall…"). Matching only the pre-figure paragraph sidesteps that mismatch.
    val cleanSnippet = snippet.replace(Regex("\\s+"), " ").trim()
    val snippetFirstParagraph = snippet.substringBefore('\n').replace(Regex("\\s+"), " ").trim()
    val searchKey = snippetFirstParagraph.take(40).ifEmpty { cleanSnippet.take(40) }
    val streamCollapsed = stream.replace(Regex("\\s+"), " ")
    val cleanBefore = textBefore.replace(Regex("\\s+"), " ").trim().takeLast(40)

    val idxWithBefore = if (cleanBefore.isNotEmpty()) {
        // Insert a single space between textBefore and snippet — the trimmed cleanBefore drops
        // the trailing space, and joining directly would produce "characterizethis" which the
        // collapsed stream ("characterize this") won't match.
        val combined = "$cleanBefore ${searchKey}".take(cleanBefore.length + 1 + searchKey.length)
        streamCollapsed.indexOf(combined).let { if (it >= 0) it + cleanBefore.length + 1 else -1 }
    } else -1
    val startInCollapsed = if (idxWithBefore >= 0) idxWithBefore else streamCollapsed.indexOf(searchKey)
    if (startInCollapsed < 0) {
        // Snippet not found — fall back to progression-scaled range (unchanged historical behaviour).
        val fallbackStart = (progression.coerceIn(0.0, 1.0) * totalChars).toLong().coerceAtLeast(0L)
        val fallbackEnd = (fallbackStart + snippet.length).coerceAtMost((totalChars - 1L).coerceAtLeast(0L))
        return fallbackStart to fallbackEnd
    }
    // Map collapsed offset back to raw offset — count characters preceding the match position.
    // Since `replace(\s+, " ")` only collapses runs of whitespace to single space and preserves
    // char count for non-whitespace, we can walk both strings in lockstep.
    var rawIdx = 0
    var collapsedIdx = 0
    while (collapsedIdx < startInCollapsed && rawIdx < stream.length) {
        val rawCh = stream[rawIdx]
        if (rawCh.isWhitespace()) {
            // Skip a run of whitespace in raw (counts as one space in collapsed).
            while (rawIdx < stream.length && stream[rawIdx].isWhitespace()) rawIdx++
            collapsedIdx++
        } else {
            rawIdx++
            collapsedIdx++
        }
    }
    val startChar = rawIdx.toLong()
    // Anchor the end as start + snippet length in the RAW stream (with its own inline whitespace).
    // A snippet from Readium already contains the paragraph-break whitespace between chunks, so
    // its length in raw chars is a close upper bound of the highlight's true extent.
    val endChar = (startChar + snippet.length.toLong()).coerceAtMost((totalChars - 1L).coerceAtLeast(0L))
    return startChar to endChar
}

/**
 * Merges the two independent figure sources feeding [EpubReaderViewModel.createHighlight]:
 * the JS-side selection-tracker stash (may carry `imageBytes` from canvas rasterisation) and the
 * Kotlin-side jsoup walk of the chapter HTML (always finds the enclosed figures but has no
 * bytes). Correlates entries by [figureHrefFilename] so a raster figure captured by both sides
 * keeps the stash's bytes. SVG entries are correlated by prefix identity of the serialised SVG
 * (fingerprint) — pass-through as-is when only one side has it. The output is stable-ordered:
 * every stash entry first (in its original order), then any Kotlin-walk entry not already
 * covered by a stash entry (in walk order).
 */
internal fun mergeEnclosedFigures(
    stashFigures: List<EmbeddedFigure>,
    htmlFigures: List<EmbeddedFigure>,
): List<EmbeddedFigure> {
    if (stashFigures.isEmpty() && htmlFigures.isEmpty()) return emptyList()
    val stashByFilename = stashFigures.mapNotNull { fig -> fig.href?.let { figureHrefFilename(it) to fig } }.toMap()
    val stashSvgPrefixes = stashFigures.mapNotNull { it.svg?.take(200) }.toSet()
    val merged = stashFigures.toMutableList()
    for (fig in htmlFigures) {
        val href = fig.href
        val svg = fig.svg
        val skip = when {
            href != null -> figureHrefFilename(href) in stashByFilename.keys
            svg != null -> svg.take(200) in stashSvgPrefixes
            else -> false
        }
        if (!skip) merged += fig.copy(order = merged.size)
    }
    return merged
}

/**
 * Returns just the filename (last path segment) of a figure href, matching the
 * `img[src$="filename"]` CSS selector `FigureBorderDecoration` builds. Used to correlate an
 * enclosed-figure href from a highlight range against a standalone `TYPE_IMAGE` annotation's
 * `imageHref`, so the latter can be merged into the former.
 */
internal fun figureHrefFilename(href: String): String {
    val trimmed = href.substringBefore('?').substringBefore('#')
    val slash = trimmed.lastIndexOf('/')
    return if (slash >= 0) trimmed.substring(slash + 1) else trimmed
}
