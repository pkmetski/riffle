package com.riffle.app.feature.reader

import com.riffle.core.database.AnnotationEntity
import com.riffle.core.models.Annotation
import com.riffle.core.models.EmbeddedFigure
import com.riffle.core.models.EmphasisStyle
import com.riffle.core.domain.countBodyChars
import org.jsoup.Jsoup

/** Result of a create-time adjacency merge: updated draft fields + IDs of annotations absorbed. */
internal data class AdjacentCreateMerge(
    val fields: MergedDraftFields,
    val victimIds: List<String>,
)

/**
 * Highlight auto-merge is formatting-aware: two highlight rows may share a colour but still
 * represent distinct edit targets when their sibling Emphasis style sets differ.
 */
internal fun highlightsWithEmphasisStyles(
    candidates: List<Annotation>,
    emphasisPool: List<Annotation>,
    expectedStyles: Set<EmphasisStyle>,
): List<Annotation> = candidates.filter {
    it.type == AnnotationEntity.TYPE_HIGHLIGHT &&
        collectMergedEmphasisStyles(emphasisPool, setOf(it.cfi)) == expectedStyles
}

/** Overlap-merge context length: how much text to snapshot either side of the merged range as
 *  the persisted textBefore/textAfter. Matches [OVERLAP_CONTEXT_LEN] used by the legacy detector. */
private const val MERGED_CONTEXT_LEN = 60

/**
 * True-range overlap detection for the draft-commit merge path (Bug 1 fix, 2026-07-19).
 *
 * Prior detector [highlightOverlapsAtSamePosition] used substring containment of one snippet by
 * the other. That misses partial overlaps where neither snippet contains the other — e.g. a new
 * selection "was nothing more than a comma…" that begins inside an existing "he was nothing more
 * than a comma on the page of History." and extends past its tail. Substring failed → no merge
 * → both highlights coexisted over the shared span (the doubled-orange bug in the video).
 *
 * The new detector operates on character-offset ranges resolved via [locateSnippetInBody] and
 * a strict interval-overlap test. Candidates whose sibling emphasis styles differ from the draft
 * are excluded so formatting boundaries remain separate edit targets. Detection is a pure
 * function so it is JVM-testable independently of DOM I/O.
 */

/** Half-open char range `[startChar, endChar)` — endChar exclusive so touching endpoints are
 *  NOT overlap (adjacent same-colour highlights are handled by the edit-time merge path). */
internal data class CharRange2(val startChar: Long, val endChar: Long)

/** Strict interval overlap on half-open ranges: `a.start < b.end && b.start < a.end`. */
internal fun charRangesOverlap(a: CharRange2, b: CharRange2): Boolean =
    a.startChar < b.endChar && b.startChar < a.endChar

/**
 * Resolve an annotation's `[startChar, endChar)` in the chapter body via [locateSnippetInBody].
 * Returns null when the snippet can't be located (legacy row without a matching context, or the
 * chapter HTML has drifted). Callers skip the pair rather than false-positive merging.
 *
 * For multi-paragraph snippets, [locateSnippetInBody]'s whitespace-tolerant fallback may match
 * a body substring shorter than `textSnippet.length` (because `readableBodyText` drops the
 * blank-only text nodes between adjacent block elements). Compute `endChar` by scanning forward
 * through the body's readable text, consuming the snippet's non-whitespace characters — that
 * gives the correct half-open end even when the raw snippet contains newlines the body doesn't.
 */
internal fun annotationCharRange(html: String, textSnippet: String, textBefore: String): CharRange2? {
    val start = locateSnippetInBody(html, textSnippet, textBefore) ?: return null
    val end = snippetEndCharInBody(html, start, textSnippet)
    if (end <= start) return null
    return CharRange2(start, end)
}

/**
 * Detect overlap victims for a new draft against every candidate existing highlight in the same
 * chapter. Returns the set of victim ids to delete AND the union range `[mergedStart, mergedEnd)`
 * spanning the draft + all formatting-compatible overlapping victims. Null when no compatible
 * overlap exists or the draft itself can't be located.
 *
 * The caller (commitDraft) uses the union range to rebuild the merged snippet / textBefore /
 * textAfter / CFI, delete each victim (cascading its sibling emphasis rows), and persist ONE
 * merged highlight — replacing the pre-fix "delete-only, don't union" logic that shrank the
 * merged span down to just the new selection.
 */
internal data class OverlapMergeResult(
    val mergedStart: Long,
    val mergedEnd: Long,
    val victimIds: List<String>,
)

/**
 * Fields of a merged (or plain) draft that get persisted by `commitDraft`. Separated from the
 * full [com.riffle.app.feature.reader.session.AnnotationSession.DraftAnnotation] so the overlap-
 * merge path can override just these fields (range/snippet/context/CFI/progression/figures) while
 * carrying the draft's identity (sourceId, itemId, chapter, spineIndex, originFontFamily, anchor)
 * through unchanged.
 */
internal data class MergedDraftFields(
    val cfiRange: String,
    val textSnippet: String,
    val textBefore: String,
    val textAfter: String,
    val progression: Double,
    val embeddedFigures: List<EmbeddedFigure>?,
)

/** Trivially lift the draft's own fields into [MergedDraftFields] for the no-overlap path. */
internal fun com.riffle.app.feature.reader.session.AnnotationSession.DraftAnnotation.toDraftFields(): MergedDraftFields =
    MergedDraftFields(
        cfiRange = cfiRange,
        textSnippet = textSnippet,
        textBefore = textBefore,
        textAfter = textAfter,
        progression = progression,
        embeddedFigures = embeddedFigures,
    )

/**
 * Rebuild the persisted highlight fields for a range-merged draft. Reads the merged snippet from
 * the DOM via [readableTextBetween], derives textBefore/textAfter from the readable body-text
 * either side of the union range (bounded by [MERGED_CONTEXT_LEN]), rebuilds the CFI via
 * [buildHighlightCfiRange], recomputes progression against the chapter's readable-char total,
 * and re-walks the merged range to collect embedded figures (with bytes/svg re-attached from the
 * pre-merge draft + victims so re-walking doesn't drop already-captured raster data).
 *
 * Returns null when any DOM-derived step fails (missing snippet in text, CFI build fails, empty
 * body), letting the caller fall back to the un-merged draft fields as a safety net.
 */
internal fun buildMergedDraftFields(
    html: String,
    draftSpineIndex: Int,
    draftEmbeddedFigures: List<EmbeddedFigure>?,
    overlap: OverlapMergeResult,
    candidates: List<Annotation>,
): MergedDraftFields? {
    val mergedStart = overlap.mergedStart
    val mergedEnd = overlap.mergedEnd
    val body = readableBodyText(html)
    val totalChars = countBodyChars(Jsoup.parse(html).body())
    if (totalChars <= 0L) return null
    val mergedSnippet = readableTextBetween(html, mergedStart, mergedEnd) ?: return null
    val spineStep = (draftSpineIndex + 1) * 2
    val cfiRange = buildHighlightCfiRange(spineStep, html, mergedStart, mergedEnd - 1L) ?: return null
    val startInt = mergedStart.toInt().coerceIn(0, body.length)
    val endInt = mergedEnd.toInt().coerceIn(0, body.length)
    val textBefore = body.substring(0, startInt).takeLast(MERGED_CONTEXT_LEN)
    val textAfter = body.substring(endInt).take(MERGED_CONTEXT_LEN)
    val progression = mergedStart.toDouble() / totalChars.toDouble()
    // Preserve raster bytes / svg the pre-merge rows had captured: index by filename first, then
    // re-walk the merged range so figure order matches the DOM.
    val victimFigures = candidates
        .filter { it.id in overlap.victimIds }
        .flatMap { it.embeddedFigures.orEmpty() }
    val walkedFigures = findEnclosedFiguresInHtml(html, mergedStart, mergedEnd - 1L)
    val figures = if (walkedFigures.isEmpty()) null else {
        val bytesByFile = mutableMapOf<String, String>()
        val svgByFile = mutableMapOf<String, String>()
        (draftEmbeddedFigures.orEmpty() + victimFigures).forEach { fig ->
            val key = fig.href?.let(::figureHrefFilename) ?: return@forEach
            fig.imageBytes?.takeIf { it.isNotBlank() }?.let { bytesByFile.putIfAbsent(key, it) }
            fig.svg?.takeIf { it.isNotBlank() }?.let { svgByFile.putIfAbsent(key, it) }
        }
        walkedFigures.mapIndexed { i, fig ->
            val key = fig.href?.let(::figureHrefFilename)
            fig.copy(
                order = i,
                imageBytes = fig.imageBytes ?: key?.let(bytesByFile::get),
                svg = fig.svg ?: key?.let(svgByFile::get),
            )
        }
    }
    return MergedDraftFields(
        cfiRange = cfiRange,
        textSnippet = mergedSnippet,
        textBefore = textBefore,
        textAfter = textAfter,
        progression = progression,
        embeddedFigures = figures,
    )
}

/**
 * Create-time adjacency merge: detect candidates that are text-adjacent to the new draft and
 * absorb them into a single highlight before persisting. Mirror of the edit-time
 * `mergeAdjacentIntoHighlight` path in EpubReaderViewModel, but runs purely on draft fields
 * (no annotation ID yet) so there is no race on `annotationSession.annotations`.
 *
 * Returns null when no adjacent neighbour is found (the common path). On success, returns the
 * updated [MergedDraftFields] spanning the full merged range and the [AdjacentCreateMerge.victimIds]
 * the caller must delete (with their sibling emphasis rows) before persisting the merged highlight.
 *
 * [candidates] should already be filtered to same-chapter TYPE_HIGHLIGHT rows with overlap victims
 * excluded so they aren't double-processed. This function additionally filters by the sibling
 * Emphasis set because that is part of a Highlight's merge identity.
 */
internal fun computeAdjacentCreateMerge(
    html: String,
    draftSnippet: String,
    draftTextBefore: String,
    draftTextAfter: String,
    draftProgression: Double,
    draftSpineIndex: Int,
    draftChapterHref: String,
    draftColor: String,
    draftEmbeddedFigures: List<EmbeddedFigure>?,
    candidates: List<Annotation>,
    draftEmphasisStyles: Set<EmphasisStyle> = emptySet(),
    emphasisPool: List<Annotation> = emptyList(),
): AdjacentCreateMerge? {
    val formattingCompatibleCandidates = highlightsWithEmphasisStyles(
        candidates = candidates,
        emphasisPool = emphasisPool,
        expectedStyles = draftEmphasisStyles,
    )
    var trialAnchor = MergeAnchor(
        spineIndex = draftSpineIndex,
        color = draftColor,
        note = null,
        textSnippet = draftSnippet,
        textBefore = draftTextBefore,
        textAfter = draftTextAfter,
        progression = draftProgression,
        chapterHref = draftChapterHref,
    )
    var leftmost = trialAnchor
    var rightmost = trialAnchor
    val toAbsorb = mutableListOf<Annotation>()
    val absorbedIds = mutableSetOf<String>()
    while (true) {
        val match = findAnyMergeableNeighbor(
            trialAnchor,
            formattingCompatibleCandidates,
            absorbedIds,
            html,
        ) ?: break
        when (match.side) {
            MergeSide.CANDIDATE_BEFORE_ANCHOR -> leftmost = match.neighbor.toMergeAnchor()
            MergeSide.CANDIDATE_AFTER_ANCHOR -> rightmost = match.neighbor.toMergeAnchor()
        }
        toAbsorb += match.neighbor
        absorbedIds += match.neighbor.id
        trialAnchor = applyMerge(trialAnchor, match)
    }
    if (toAbsorb.isEmpty()) return null
    // Rebuild the merged range from DOM positions — same as mergeAdjacentIntoHighlight.
    val totalChars = countBodyChars(Jsoup.parse(html).body())
    if (totalChars <= 0L) return null
    val startChar = locateSnippetInBody(html, leftmost.textSnippet, leftmost.textBefore) ?: return null
    val rightmostStart = locateSnippetInBody(html, rightmost.textSnippet, rightmost.textBefore) ?: return null
    val endChar = snippetEndCharInBody(html, rightmostStart, rightmost.textSnippet).coerceAtMost(totalChars)
    if (endChar <= startChar) return null
    val domSnippet = readableTextBetween(html, startChar, endChar) ?: return null
    // Use the DOM text to validate the assembled range, but persist the Readium-composed quote.
    // The readable-char model drops paragraph / <br> boundaries; the composed quote retains the
    // newline that every reader mode needs to resolve and paint a cross-line annotation.
    val mergedSnippet = validatedMergedSnippet(domSnippet, trialAnchor.textSnippet) ?: return null
    val spineStep = (draftSpineIndex + 1) * 2
    val cfiRange = buildHighlightCfiRange(spineStep, html, startChar, endChar - 1L) ?: return null
    val body = readableBodyText(html)
    val startInt = startChar.toInt().coerceIn(0, body.length)
    val endInt = endChar.toInt().coerceIn(0, body.length)
    val textBefore = body.substring(0, startInt).takeLast(MERGED_CONTEXT_LEN)
    val textAfter = body.substring(endInt).take(MERGED_CONTEXT_LEN)
    val progression = startChar.toDouble() / totalChars.toDouble()
    val victimFigures = toAbsorb.flatMap { it.embeddedFigures.orEmpty() }
    val walkedFigures = findEnclosedFiguresInHtml(html, startChar, endChar - 1L)
    val figures = if (walkedFigures.isEmpty()) null else {
        val bytesByFile = mutableMapOf<String, String>()
        val svgByFile = mutableMapOf<String, String>()
        (draftEmbeddedFigures.orEmpty() + victimFigures).forEach { fig ->
            val key = fig.href?.let(::figureHrefFilename) ?: return@forEach
            fig.imageBytes?.takeIf { it.isNotBlank() }?.let { bytesByFile.putIfAbsent(key, it) }
            fig.svg?.takeIf { it.isNotBlank() }?.let { svgByFile.putIfAbsent(key, it) }
        }
        walkedFigures.mapIndexed { i, fig ->
            val key = fig.href?.let(::figureHrefFilename)
            fig.copy(
                order = i,
                imageBytes = fig.imageBytes ?: key?.let(bytesByFile::get),
                svg = fig.svg ?: key?.let(svgByFile::get),
            )
        }
    }
    return AdjacentCreateMerge(
        fields = MergedDraftFields(
            cfiRange = cfiRange,
            textSnippet = mergedSnippet,
            textBefore = textBefore,
            textAfter = textAfter,
            progression = progression,
            embeddedFigures = figures,
        ),
        victimIds = toAbsorb.map { it.id },
    )
}

internal fun computeOverlapMerge(
    html: String,
    draftSnippet: String,
    draftTextBefore: String,
    candidates: List<Annotation>,
    draftEmphasisStyles: Set<EmphasisStyle> = emptySet(),
    emphasisPool: List<Annotation> = emptyList(),
): OverlapMergeResult? {
    val draftRange = annotationCharRange(html, draftSnippet, draftTextBefore) ?: return null
    var mergedStart = draftRange.startChar
    var mergedEnd = draftRange.endChar
    val victims = mutableListOf<String>()
    val formattingCompatibleCandidates = highlightsWithEmphasisStyles(
        candidates = candidates,
        emphasisPool = emphasisPool,
        expectedStyles = draftEmphasisStyles,
    )
    for (candidate in formattingCompatibleCandidates) {
        val range = annotationCharRange(html, candidate.textSnippet, candidate.textBefore) ?: continue
        if (!charRangesOverlap(draftRange, range)) continue
        victims += candidate.id
        if (range.startChar < mergedStart) mergedStart = range.startChar
        if (range.endChar > mergedEnd) mergedEnd = range.endChar
    }
    if (victims.isEmpty()) return null
    return OverlapMergeResult(mergedStart, mergedEnd, victims)
}
