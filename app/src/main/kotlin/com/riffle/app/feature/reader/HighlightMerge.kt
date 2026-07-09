package com.riffle.app.feature.reader

import com.riffle.core.database.AnnotationEntity
import com.riffle.core.domain.Annotation
import com.riffle.core.domain.normalizeEpubHref

// Auto-merge adjacent highlights (2026-07-05 spec). Pure text-anchored logic so both the
// create-time path (fresh selection, no id yet) and the edit-time path (existing row that just
// changed colour or had its note cleared) can drive the same merge decision. See:
//   docs/superpowers/specs/2026-07-05-highlight-auto-merge-design.md

/** Which side of the anchor the candidate sits on in the reading order. */
internal enum class MergeSide { CANDIDATE_BEFORE_ANCHOR, CANDIDATE_AFTER_ANCHOR }

/**
 * A concrete adjacency match: which neighbour to absorb, which side, and the whitespace run to
 * preserve between the two snippets when concatenating.
 */
internal data class MergeCandidate(
    val neighbor: Annotation,
    val side: MergeSide,
    val whitespaceBetween: String,
)

/**
 * The mutable state of a highlight being grown by merges.
 *
 * At create-time the caller seeds this from the user's selection; at edit-time it's seeded from
 * the row that just changed. Either way merging mutates only these fields — CFI is rebuilt from
 * `progression` + `textSnippet` at the end.
 */
internal data class MergeAnchor(
    val spineIndex: Int,
    val color: String,
    val note: String?,
    val textSnippet: String,
    val textBefore: String,
    val textAfter: String,
    val progression: Double,
    val chapterHref: String,
)

internal fun Annotation.toMergeAnchor(): MergeAnchor = MergeAnchor(
    spineIndex = spineIndex,
    color = color,
    note = note,
    textSnippet = textSnippet,
    textBefore = textBefore,
    textAfter = textAfter,
    progression = progression,
    chapterHref = chapterHref,
)

/**
 * Static eligibility gate — same chapter, same colour, both notes empty/blank. Accepts either
 * TYPE_HIGHLIGHT or TYPE_IMAGE candidates: annotations do not distinguish text from graph — a
 * user's mental model treats them identically, so a figure adjacent to a same-colour highlight is
 * as eligible for absorption as another text highlight would be. See feedback memory
 * `annotations-text-graph-symmetric`. Adjacency (which decides WHERE in the range the candidate
 * sits) is a separate check in [findAdjacency] / [findFigureAdjacency].
 */
internal fun isMergeEligible(anchor: MergeAnchor, candidate: Annotation): Boolean {
    if (candidate.type != AnnotationEntity.TYPE_HIGHLIGHT &&
        candidate.type != AnnotationEntity.TYPE_IMAGE
    ) return false
    if (anchor.spineIndex != candidate.spineIndex) return false
    if (!anchor.color.equals(candidate.color, ignoreCase = true)) return false
    if (!anchor.note.isNullOrBlank()) return false
    if (!candidate.note.isNullOrBlank()) return false
    return true
}

/**
 * Detect text-adjacency between [anchor] and [candidate]. Returns non-null iff the candidate's
 * snippet is exactly what sits immediately after (or before) the anchor's snippet in the DOM —
 * modulo a single run of whitespace between them.
 *
 * Both sides' text contexts are checked (bidirectional): Readium bounds `textBefore`/`textAfter` to
 * a fixed window, so a long snippet may not fit in one side's context but does fit in the other's.
 * As long as EITHER side's context proves the abutment, we accept — the two views must agree since
 * they're both extracted from the same DOM text.
 *
 * "Modulo a single whitespace run" means zero-or-more whitespace chars, all contiguous. Any
 * non-whitespace character between = not adjacent.
 *
 * A void figure element (`<img>`) between the two counts as zero readable chars in the stream that
 * Readium uses to build `textBefore`/`textAfter`, so this same textual check catches figures that
 * sit visually between two adjacent same-colour text highlights — that's the desired behaviour
 * (feedback memory `annotations-text-graph-symmetric`): text-with-figure-between is still text-
 * adjacent. The figure gets absorbed as an embedded figure at merge time via
 * `findAbsorbableImageAnnotations` / the create-time range walk.
 */
internal fun findAdjacency(anchor: MergeAnchor, candidate: Annotation): MergeCandidate? {
    val anchorSnippet = anchor.textSnippet.takeIf { it.isNotBlank() } ?: return null
    val neighborSnippet = candidate.textSnippet.takeIf { it.isNotBlank() } ?: return null

    // Candidate sits AFTER anchor:
    //   • anchor.textAfter starts with [ws][neighborSnippet], OR
    //   • candidate.textBefore ends with [anchorSnippet][ws]
    matchAtStart(anchor.textAfter, neighborSnippet)?.let { ws ->
        return MergeCandidate(candidate, MergeSide.CANDIDATE_AFTER_ANCHOR, ws)
    }
    matchAtEnd(candidate.textBefore, anchorSnippet)?.let { ws ->
        return MergeCandidate(candidate, MergeSide.CANDIDATE_AFTER_ANCHOR, ws)
    }

    // Candidate sits BEFORE anchor:
    //   • anchor.textBefore ends with [neighborSnippet][ws], OR
    //   • candidate.textAfter starts with [ws][anchorSnippet]
    matchAtEnd(anchor.textBefore, neighborSnippet)?.let { ws ->
        return MergeCandidate(candidate, MergeSide.CANDIDATE_BEFORE_ANCHOR, ws)
    }
    matchAtStart(candidate.textAfter, anchorSnippet)?.let { ws ->
        return MergeCandidate(candidate, MergeSide.CANDIDATE_BEFORE_ANCHOR, ws)
    }
    return null
}

/**
 * Whitespace-normalised prefix match. Returns the raw whitespace prefix from [context] if, after
 * collapsing runs of whitespace to a single space and stripping outer whitespace, [context] begins
 * with [target] (or [target]'s first ~15 chars — enough uniqueness — when [target] is longer than
 * [context] allows). Null otherwise.
 *
 * Whitespace normalisation is essential: Readium can capture the same DOM whitespace differently
 * across `Locator.text.highlight` vs `Locator.text.after`/`before` (single space vs NBSP vs
 * multiple runs after DOM traversal), so a byte-exact comparison misses many genuinely-adjacent
 * highlights.
 */
private fun matchAtStart(context: String, target: String): String? {
    if (context.isEmpty() || target.isBlank()) return null
    val normContext = normaliseWs(context).trimStart()
    val normTarget = normaliseWs(target).trim()
    if (normTarget.isEmpty()) return null
    val prefixLen = minOf(normTarget.length, normContext.length)
    if (prefixLen < MIN_MATCH_CHARS && prefixLen < normTarget.length) return null
    if (!normContext.regionMatches(0, normTarget, 0, prefixLen, ignoreCase = false)) return null
    var i = 0
    while (i < context.length && context[i].isWhitespace()) i++
    return context.substring(0, i)
}

/** Symmetric to [matchAtStart] at the tail end. */
private fun matchAtEnd(context: String, target: String): String? {
    if (context.isEmpty() || target.isBlank()) return null
    val normContext = normaliseWs(context).trimEnd()
    val normTarget = normaliseWs(target).trim()
    if (normTarget.isEmpty()) return null
    val suffixLen = minOf(normTarget.length, normContext.length)
    if (suffixLen < MIN_MATCH_CHARS && suffixLen < normTarget.length) return null
    val ctxOffset = normContext.length - suffixLen
    val tgtOffset = normTarget.length - suffixLen
    if (!normContext.regionMatches(ctxOffset, normTarget, tgtOffset, suffixLen, ignoreCase = false)) return null
    var i = context.length
    while (i > 0 && context[i - 1].isWhitespace()) i--
    return context.substring(i)
}

/** Collapse every run of whitespace (any Unicode WS) to a single ASCII space. */
private fun normaliseWs(s: String): String {
    val out = StringBuilder(s.length)
    var lastWasWs = false
    for (ch in s) {
        if (ch.isWhitespace()) {
            if (!lastWasWs) out.append(' ')
            lastWasWs = true
        } else {
            out.append(ch)
            lastWasWs = false
        }
    }
    return out.toString()
}

/**
 * Minimum number of characters that must line up when the context is TOO SHORT to hold the full
 * target (e.g. Readium bounds textAfter to ~30 chars but the target snippet is 100 chars). Below
 * this floor we don't trust the match — a 5-char coincidence is too easy to hit randomly.
 */
private const val MIN_MATCH_CHARS = 12

/**
 * Apply a merge: produce a new anchor whose snippet spans both sides and whose text-before /
 * text-after / progression are inherited from the outermost endpoints.
 *
 * For a TYPE_IMAGE candidate the text is unchanged — the figure is absorbed into the merged
 * highlight's `embeddedFigures` later by the commit path, not into `textSnippet`. The anchor's
 * spatial extent still needs to grow so the merged CFI covers the figure position; that spatial
 * extension is done by the commit path via the figure's `charOffset`, not here.
 */
internal fun applyMerge(anchor: MergeAnchor, match: MergeCandidate): MergeAnchor {
    val neighbor = match.neighbor
    if (neighbor.type == AnnotationEntity.TYPE_IMAGE) {
        // Text stays the same. Note stays empty (both sides eligibility-checked as no-note). No
        // textBefore/textAfter change either — the figure is invisible in the readable-text stream.
        return anchor
    }
    return when (match.side) {
        MergeSide.CANDIDATE_AFTER_ANCHOR -> anchor.copy(
            textSnippet = anchor.textSnippet + match.whitespaceBetween + neighbor.textSnippet,
            textAfter = neighbor.textAfter,
        )
        MergeSide.CANDIDATE_BEFORE_ANCHOR -> anchor.copy(
            textSnippet = neighbor.textSnippet + match.whitespaceBetween + anchor.textSnippet,
            textBefore = neighbor.textBefore,
            progression = neighbor.progression,
        )
    }
}

/**
 * Find any eligible + adjacent neighbour in [pool]. Returns the first match; the caller loops
 * (chain-merge until no more matches). [excludeIds] carries the anchor's own id (if it already
 * exists) plus any neighbours already absorbed this round.
 *
 * TYPE_HIGHLIGHT candidates use [findAdjacency]'s text-context check. TYPE_IMAGE candidates use
 * [findFigureAdjacency], which needs the chapter [html] to locate the figure's DOM position and
 * compare it against the anchor's range endpoints; when [html] is null (unit-test paths without a
 * chapter body), TYPE_IMAGE candidates are silently skipped.
 */
internal fun findAnyMergeableNeighbor(
    anchor: MergeAnchor,
    pool: List<Annotation>,
    excludeIds: Set<String>,
    html: String? = null,
): MergeCandidate? {
    for (candidate in pool) {
        if (candidate.id in excludeIds) continue
        if (!isMergeEligible(anchor, candidate)) continue
        val adjacency = when (candidate.type) {
            AnnotationEntity.TYPE_HIGHLIGHT -> findAdjacency(anchor, candidate)
            AnnotationEntity.TYPE_IMAGE ->
                if (html != null) findFigureAdjacency(html, anchor, candidate) else null
            else -> null
        } ?: continue
        return adjacency
    }
    return null
}

/**
 * Figure-adjacency: does the [imageCandidate]'s figure sit touching the [anchor]'s text range in
 * the chapter DOM? "Touching" means the figure's readable-text position (as counted by
 * `findEnclosedFiguresInHtml`) is at either endpoint of the anchor's range (widened by 1 char on
 * each side to catch void figures whose zero-char span coincides with the boundary), or strictly
 * inside the range.
 *
 * Symmetric to [findAdjacency] for text neighbours — TYPE_IMAGE candidates are merged in the same
 * way (feedback memory `annotations-text-graph-symmetric`): the outcome absorbs the figure into
 * the merged highlight's embedded figures. Returns the [MergeCandidate] with an empty
 * whitespace-between run (figures are void — no whitespace to preserve).
 */
internal fun findFigureAdjacency(
    html: String,
    anchor: MergeAnchor,
    imageCandidate: Annotation,
): MergeCandidate? {
    if (imageCandidate.type != AnnotationEntity.TYPE_IMAGE) return null
    val figureHref = imageCandidate.imageHref ?: return null
    val anchorStart = locateSnippetInBody(html, anchor.textSnippet, anchor.textBefore) ?: return null
    val anchorEnd = anchorStart + anchor.textSnippet.length
    val enclosed = findEnclosedFiguresInHtml(
        html,
        (anchorStart - 1L).coerceAtLeast(0L),
        anchorEnd + 1L,
    )
    if (enclosed.isEmpty()) return null
    val filename = figureHrefFilename(figureHref)
    val match = enclosed.firstOrNull { it.href?.let(::figureHrefFilename) == filename } ?: return null
    // Determine which side of the anchor the figure sits on so downstream commit logic can extend
    // the merged range correctly. `charOffset` is measured from the anchor's start.
    val offset = match.charOffset ?: 0L
    val side = if (offset >= (anchor.textSnippet.length / 2).toLong()) {
        MergeSide.CANDIDATE_AFTER_ANCHOR
    } else {
        MergeSide.CANDIDATE_BEFORE_ANCHOR
    }
    return MergeCandidate(imageCandidate, side, whitespaceBetween = "")
}

/**
 * Standalone TYPE_IMAGE annotations that are absorbable by the text highlight anchored at
 * ([snippet], [textBefore]) — i.e. their figure sits inside (or immediately adjacent to) the
 * highlight's char range in [html] AND they share the highlight's [color] and [chapterHref] AND
 * carry no note. Called by `mergeAdjacentIntoHighlight` (fix #1, 2026-07-09) so a TYPE_IMAGE
 * annotated separately on a figure the highlight now covers gets collapsed on the next popup
 * dismiss — the same rule the create-time absorbedFilenames loop enforces, extended to edit-time.
 *
 * Adjacency uses a 1-char widening on each side of the range so a figure sitting exactly AT the
 * boundary (its position coincides with the range start or end) is still caught — void figures
 * contribute zero readable chars so their position equals the surrounding text position.
 *
 * [excludeIds] carries the anchor's own id plus any candidates already absorbed by the text-merge
 * pass, so we never re-consider the anchor or double-absorb the same row.
 */
internal fun findAbsorbableImageAnnotations(
    html: String,
    snippet: String,
    textBefore: String,
    color: String,
    chapterHref: String,
    pool: List<Annotation>,
    excludeIds: Set<String>,
): List<Annotation> {
    val start = locateSnippetInBody(html, snippet, textBefore) ?: return emptyList()
    val end = start + snippet.length
    val figures = findEnclosedFiguresInHtml(
        html,
        (start - 1L).coerceAtLeast(0L),
        end + 1L,
    )
    if (figures.isEmpty()) return emptyList()
    val enclosedFilenames = figures.mapNotNull { it.href }.map(::figureHrefFilename).toSet()
    if (enclosedFilenames.isEmpty()) return emptyList()
    return pool.filter { ann ->
        ann.id !in excludeIds &&
            ann.type == AnnotationEntity.TYPE_IMAGE &&
            ann.color.equals(color, ignoreCase = true) &&
            ann.note.isNullOrBlank() &&
            normalizeEpubHref(ann.chapterHref) == normalizeEpubHref(chapterHref) &&
            ann.imageHref?.let(::figureHrefFilename) in enclosedFilenames
    }
}

