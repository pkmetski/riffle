package com.riffle.feature.reader

import com.riffle.core.domain.epubCfiOps
import com.riffle.core.domain.epubChapterDomOps
import com.riffle.core.models.EmbeddedFigure

// Character-stream positioning for highlights (ADR 0028). Everything here works on the chapter's
// *readable* text — the concatenation of its non-blank text nodes — so the same char offsets the
// CFI machinery uses (EpubTextChars.countReadableChars) index into it directly.
//
// Moved out of `app` for issue #1066: the only DOM-bound steps are behind
// `com.riffle.core.domain.EpubChapterDomOps`, so all of this is shared and its tests run on iOS.

/**
 * Concatenation of the body's readable text — blank-only text nodes skipped. This is the "flat"
 * view of the chapter that `countBodyChars` measures; all char offsets used by highlight merging
 * are indices into this string.
 */
fun readableBodyText(html: String): String = epubChapterDomOps().readableBodyText(html)

/**
 * Readable characters in the chapter body. Identical to
 * `com.riffle.core.domain.EpubTextChars.countReadableChars` by construction — both sum the lengths
 * of the same non-blank text nodes — and pinned as such by `HighlightRangeOverlapTest`.
 */
fun countReadableBodyChars(html: String): Long = readableBodyText(html).length.toLong()

/**
 * Build a range CFI spanning [startChar]..[endChar] (body character offsets, blank-text-node
 * aware) within the chapter [html] at spine step [spineStep] (i.e. `/6/<spineStep>`). Returns null
 * when either offset falls outside the document.
 */
fun buildHighlightCfiRange(spineStep: Int, html: String, startChar: Long, endChar: Long): String? =
    epubChapterDomOps().buildCfiRange(spineStep, html, startChar, endChar)

/**
 * Build a range CFI from a Readium selection's start [startProgression] and its [selectedText].
 * The end is the start advanced by the selected text's length in the char-count model — exact for
 * single-node selections and a close best-effort across nodes (the end point is not load-bearing;
 * the start point and text snippet re-anchor the highlight). Returns null for blank selections or
 * when the start falls outside the document.
 */
fun buildHighlightCfiRangeForSelection(
    spineStep: Int,
    html: String,
    startProgression: Double,
    selectedText: String,
): String? {
    if (selectedText.isBlank()) return null
    val totalChars = countReadableBodyChars(html)
    if (totalChars == 0L) return null

    val startChar = (startProgression.coerceIn(0.0, 1.0) * totalChars).toLong().coerceIn(0L, totalChars - 1L)
    val endChar = (startChar + selectedText.length).coerceAtMost(totalChars - 1L)
    return buildHighlightCfiRange(spineStep, html, startChar, endChar)
}

/**
 * The absolute doc path of a range CFI's start point — `<commonParent><startRemainder>` — suitable
 * for `cfiDocPathToProgression`. Returns null if [rangeCfi] is not a well-formed range.
 */
fun rangeStartDocPath(rangeCfi: String): String? {
    val docPath = epubCfiOps().extractDocPath(rangeCfi) ?: return null
    val parts = docPath.split(',')
    if (parts.size != 3) return null
    val parent = parts[0].trimEnd('/')
    val startRemainder = parts[1]
    return parent + startRemainder
}

/**
 * The within-chapter progression of a stored highlight's start, used to re-anchor (re-render) the
 * highlight on reopen. Returns null when [rangeCfi] is not a range or its start can't be located in
 * [html].
 */
fun highlightStartProgression(rangeCfi: String, html: String): Double? {
    val startDocPath = rangeStartDocPath(rangeCfi) ?: return null
    return epubCfiOps().docPathToProgression(startDocPath, html)
}

/**
 * Locate the char position of [snippet] in [html]'s readable-text stream, using [before]'s last
 * ~30 chars as a disambiguating anchor. Returns the start-char of [snippet] in the readable
 * stream, or null if not locatable / ambiguous.
 *
 * Why we need this: `Locator.locations.progression` from a paginated Readium selection is the
 * *page*'s progression, not the selection's. Every highlight created on the same page gets the
 * same stored progression and CFI start offset, so the persisted char-position is useless for
 * pinning down the selection's true location. Text-searching the DOM with the surrounding-text
 * anchor recovers the real position.
 */
fun locateSnippetInBody(
    html: String,
    snippet: String,
    before: String,
): Long? {
    if (snippet.isBlank()) return null
    val body = readableBodyText(html)
    val anchorTail = before.takeLast(30).trimStart()
    if (anchorTail.isNotEmpty()) {
        val query = anchorTail + snippet
        val idx = body.indexOf(query)
        if (idx >= 0) return (idx + anchorTail.length).toLong()
    }
    // Anchor-less fallback: only trustworthy if the snippet appears exactly once in the chapter.
    val first = body.indexOf(snippet)
    if (first >= 0 && body.indexOf(snippet, first + 1) < 0) return first.toLong()
    // Whitespace-tolerant fallback (2026-07-19): a multi-paragraph selection has "\n" or spaces
    // between paragraphs in the Readium-captured snippet, but [readableBodyText] concatenates
    // adjacent block-element text nodes with NO separator (a blank-only text node between
    // `<p>` elements is skipped). Verbatim indexOf then fails on cross-paragraph snippets, so
    // the overlap-merge detector treats overlapping multi-paragraph selections as disjoint and
    // both rows persist as a doubly-annotated span. Rebuild a regex from the snippet where every
    // whitespace run becomes `\s*` — matches whether or not the body carries the separator, and
    // stays unambiguous because the surrounding non-whitespace tokens are still order-sensitive.
    val tokens = snippet.split(Regex("\\s+")).filter { it.isNotEmpty() }
    if (tokens.isEmpty()) return null
    val pattern = tokens.joinToString(separator = "\\s*") { Regex.escape(it) }
    val regex = try { Regex(pattern) } catch (_: Throwable) { return null }
    val anchoredMatch = if (anchorTail.isNotEmpty()) {
        val anchorTokens = anchorTail.split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (anchorTokens.isEmpty()) {
            null
        } else {
            val anchorPattern = anchorTokens.joinToString(separator = "\\s*") { Regex.escape(it) }
            val combined = try { Regex("$anchorPattern\\s*$pattern") } catch (_: Throwable) { null }
            combined?.find(body)?.let { m ->
                val snippetStart = Regex(pattern).find(body, m.range.first)?.range?.first
                snippetStart?.toLong()
            }
        }
    } else {
        null
    }
    if (anchoredMatch != null) return anchoredMatch
    val loose = regex.find(body) ?: return null
    val next = regex.find(body, loose.range.first + 1)
    if (next != null) return null
    return loose.range.first.toLong()
}

/**
 * Compute the half-open end char of a snippet located at [startChar] in [html]'s readable body,
 * by walking the body forward and consuming as many non-whitespace chars as the snippet has.
 * Handles the multi-paragraph case where `snippet.length` overshoots the actual body span: a
 * cross-paragraph snippet like "para1.\nThe" is 10 chars, but the readable body concatenates
 * text nodes with no separator ("para1.The"), so the actual span is 9 chars.
 *
 * Callers use this instead of `startChar + snippet.length` when the snippet may cross a block
 * boundary — otherwise the returned range would extend past the user's selection, and highlight
 * decorations would paint over adjacent text they shouldn't (reported 2026-07-20: "wash extends
 * to whole paragraph after Annotate").
 */
fun snippetEndCharInBody(html: String, startChar: Long, snippet: String): Long {
    val body = readableBodyText(html)
    val nonWs = snippet.count { !it.isWhitespace() }
    if (nonWs <= 0) return startChar
    var idx = startChar.toInt().coerceAtLeast(0).coerceAtMost(body.length)
    var consumed = 0
    while (idx < body.length && consumed < nonWs) {
        if (!body[idx].isWhitespace()) consumed++
        idx++
    }
    return idx.toLong()
}

/**
 * Extract the readable-text substring of [html]'s body between [startChar] (inclusive) and
 * [endChar] (exclusive) — using the same "readable char" walk as [readableBodyText] (blank-only
 * text nodes skipped). Returns null if the range is empty or falls outside the doc.
 *
 * Used by the highlight-merge path so the stored merged `textSnippet` is byte-exact against the
 * DOM. Concatenating the two source snippets with a captured whitespace run can drift from the
 * actual DOM (Readium's `textAfter`/`textBefore` may normalise NBSP/newlines differently than
 * `textSnippet`), and any drift makes Readium's decorator fail-then-fallback to a partial range.
 */
fun readableTextBetween(html: String, startChar: Long, endChar: Long): String? {
    if (endChar <= startChar) return null
    val body = readableBodyText(html)
    val from = startChar.coerceIn(0L, body.length.toLong()).toInt()
    val to = endChar.coerceIn(from.toLong(), body.length.toLong()).toInt()
    return body.substring(from, to).takeIf { it.isNotEmpty() }
}

/**
 * Given a chapter's XHTML and the body-char range a highlight covers, return the `<img>` / `<svg>`
 * / `<picture>` / `<figure>` elements enclosed by that range, as [EmbeddedFigure]s.
 *
 * This exists because the JS-side walker inside `SELECTION_SPAN_TRACKER_JS` silently missed
 * figures in paginated Readium: the tracker's `selectionchange` fires, the bridge is reachable
 * (rect popup positioning works), but the figures never made it into `SelectionFiguresStash` —
 * highlights spanning an equation image landed in the DB with empty `embeddedFigures` and no
 * border was drawn. Running the walk on the already-loaded chapter HTML sidesteps every
 * WebView / range-selection / timing concern.
 *
 * A figure is "enclosed" ONLY when the highlight STRADDLES it — the range must include text-stream
 * chars on BOTH sides. A range that ends exactly AT the figure's position does NOT cover it:
 * visually the highlight stops just before the figure, and marking it enclosed would give the user
 * "text-before-only was highlighted → figure got marked too". Symmetric for the leading edge. For
 * non-void figure-container tags we accept overlap of the interior — a highlight that opens inside
 * the figure's own text still straddles it.
 */
fun findEnclosedFiguresInHtml(
    html: String,
    startChar: Long,
    endChar: Long,
): List<EmbeddedFigure> {
    if (startChar < 0 || endChar < startChar) return emptyList()
    val figures = mutableListOf<EmbeddedFigure>()
    val seen = HashSet<Int>()
    var order = 0

    for (raw in epubChapterDomOps().figures(html)) {
        val overlaps = if (raw.startChar == raw.endChar) {
            startChar < raw.startChar && raw.startChar < endChar
        } else {
            startChar < raw.endChar && raw.startChar < endChar
        }
        if (!overlaps) continue
        if (!seen.add(raw.targetId)) continue
        // Char offset relative to the highlight's start — where this figure sits in the
        // highlight's readable-text stream. Populated so the annotations panel and elided
        // reader can split the snippet at the true figure position (fix 2026-07-09).
        // Clamped to zero: a figure sitting BEFORE the highlight start (caught only via
        // the boundary-widening in the absorb-adjacent path) reports offset 0.
        val offsetInSnippet = (raw.startChar - startChar).coerceAtLeast(0L)
        figures += if (raw.targetTag == "svg") {
            EmbeddedFigure(
                href = null,
                svg = raw.svgOuterHtml,
                caption = raw.caption,
                order = order++,
                charOffset = offsetInSnippet,
            )
        } else {
            EmbeddedFigure(
                href = raw.imageSrc,
                svg = null,
                caption = raw.caption,
                order = order++,
                charOffset = offsetInSnippet,
            )
        }
    }
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
 * zero enclosed figures and the highlight lands without embeddedFigures.
 *
 * Searching for the snippet string against the readable-text stream gives an EXACT anchor. When
 * the search fails (snippet spans elements weirdly, or the HTML has been reformatted), falls back
 * to the progression-scaled range so the resolver stays best-effort. `textBefore` disambiguates
 * repeated snippets — earliest match after `textBefore` wins; when both are supplied, we anchor
 * on `textBefore + snippet` and drop the textBefore prefix. Returns the pair (startChar, endChar).
 */
fun anchorRangeToSnippet(
    html: String,
    snippet: String,
    textBefore: String,
    progression: Double,
): Pair<Long, Long> {
    // The body text stream is built the same way blank-aware char-counting is (matching
    // `findNodeAtChar` semantics): non-blank text nodes concatenated verbatim.
    val stream = readableBodyText(html)
    val totalChars = stream.length.toLong()

    // Try textBefore + snippet first — most robust anchor for repeated snippets. Text-node
    // boundaries collapse the "\n\n" between paragraphs to nothing in the stream, so strip such
    // markers from the search key too. Anchor keys use the FIRST paragraph of the snippet only:
    // a snippet that crosses an enclosed figure (like an equation image between "way:" and
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
        val combined = "$cleanBefore $searchKey".take(cleanBefore.length + 1 + searchKey.length)
        streamCollapsed.indexOf(combined).let { if (it >= 0) it + cleanBefore.length + 1 else -1 }
    } else {
        -1
    }
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
 * Merges the two independent figure sources feeding `EpubReaderViewModel.createHighlight`:
 * the JS-side selection-tracker stash (may carry `imageBytes` from canvas rasterisation) and the
 * Kotlin-side DOM walk of the chapter HTML (always finds the enclosed figures but has no bytes).
 * Correlates entries by [figureHrefFilename] so a raster figure captured by both sides keeps the
 * stash's bytes. SVG entries are correlated by prefix identity of the serialised SVG
 * (fingerprint) — pass-through as-is when only one side has it. The output is stable-ordered:
 * every stash entry first (in its original order), then any walk entry not already covered by a
 * stash entry (in walk order).
 */
fun mergeEnclosedFigures(
    stashFigures: List<EmbeddedFigure>,
    htmlFigures: List<EmbeddedFigure>,
): List<EmbeddedFigure> {
    if (stashFigures.isEmpty() && htmlFigures.isEmpty()) return emptyList()
    val stashByFilename = stashFigures.mapNotNull { fig -> fig.href?.let { figureHrefFilename(it) to fig } }.toMap()
    val stashSvgPrefixes = stashFigures.mapNotNull { it.svg?.take(200) }.toSet()
    val merged = stashFigures.toMutableList()
    // Promote charOffset from walk entries into stash entries that lack it. The JS stash never
    // captures charOffset (the bridge has no char-counting logic); the Kotlin walk always does.
    // Without promotion, figure.charOffset stays null and highlightOverlapsCaption returns false
    // for the null branch → tintCaption=true → CSS double-paints the figcaption.
    val walkByFilename = htmlFigures.mapNotNull { fig -> fig.href?.let { figureHrefFilename(it) to fig } }.toMap()
    for (i in merged.indices) {
        val stashFig = merged[i]
        val stashHref = stashFig.href
        if (stashFig.charOffset == null && stashHref != null) {
            val walkOffset = walkByFilename[figureHrefFilename(stashHref)]?.charOffset
            if (walkOffset != null) merged[i] = stashFig.copy(charOffset = walkOffset)
        }
    }
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
fun figureHrefFilename(href: String): String {
    val trimmed = href.substringBefore('?').substringBefore('#')
    val slash = trimmed.lastIndexOf('/')
    return if (slash >= 0) trimmed.substring(slash + 1) else trimmed
}
