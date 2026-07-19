package com.riffle.app.feature.reader

import com.riffle.core.database.AnnotationEntity
import com.riffle.core.models.Annotation
import com.riffle.core.models.EmphasisStyle
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
    /** ADR 0046: which annotation type the anchor represents. Match key varies by type — colour
     *  for [AnnotationEntity.TYPE_HIGHLIGHT], styles set for [AnnotationEntity.TYPE_EMPHASIS] —
     *  so an emphasis anchor never absorbs a highlight neighbour and vice versa. Defaults to
     *  TYPE_HIGHLIGHT so existing test call-sites that construct MergeAnchor directly keep
     *  their current semantics. */
    val type: String = AnnotationEntity.TYPE_HIGHLIGHT,
    /** ADR 0046: set-valued match key for [AnnotationEntity.TYPE_EMPHASIS] anchors. Null on
     *  every other type. Two emphasis rows merge iff their sets are equal AND non-empty. */
    val emphasisStyles: Set<EmphasisStyle>? = null,
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
    type = type,
    emphasisStyles = emphasisStyles,
)

/**
 * Static eligibility gate — same chapter, same colour, both notes empty/blank. Only TYPE_HIGHLIGHT
 * candidates are eligible for auto-merge (revised 2026-07-10): a standalone TYPE_IMAGE annotation
 * (long-press-created) represents distinct user intent and is never swept into an adjacent text
 * highlight. Symmetric: two text highlights sitting either side of a figure are NOT merged either —
 * see the figure-gap check in [findAnyMergeableNeighbor]. A figure only becomes part of a highlight
 * when the user's single selection covers text on BOTH sides of the figure at creation time (see
 * `createHighlight`'s `findEnclosedFiguresInHtml` walk).
 */
internal fun isMergeEligible(anchor: MergeAnchor, candidate: Annotation): Boolean {
    if (anchor.type != candidate.type) return false
    if (anchor.spineIndex != candidate.spineIndex) return false
    return when (anchor.type) {
        AnnotationEntity.TYPE_HIGHLIGHT -> {
            if (!anchor.color.equals(candidate.color, ignoreCase = true)) return false
            if (!anchor.note.isNullOrBlank()) return false
            if (!candidate.note.isNullOrBlank()) return false
            true
        }
        AnnotationEntity.TYPE_EMPHASIS -> {
            // ADR 0046: mirror of the highlight (colour, no-note) gate — same-styles-set match,
            // no-note gate collapsed (Emphasis has no note field). Empty sets never merge; that
            // state isn't a legal Emphasis row, but be defensive.
            val anchorStyles = anchor.emphasisStyles?.takeIf { it.isNotEmpty() } ?: return false
            val candidateStyles = candidate.emphasisStyles?.takeIf { it.isNotEmpty() } ?: return false
            anchorStyles == candidateStyles
        }
        else -> false
    }
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
 * Void figure elements (`<img>`) contribute zero readable chars, so this textual check ALONE
 * would report "text-adjacent" even when a figure sits visually between the two highlights. That's
 * not the intent: two separately-created highlights either side of a figure represent two
 * independent user actions and must NOT merge (would silently pull the figure into the merged
 * highlight's embedded figures). [findAnyMergeableNeighbor] runs a follow-up figure-gap check
 * against the chapter HTML to reject that case; a figure only becomes part of a highlight when
 * the user's single selection covers text on both sides of the figure at creation time.
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
 * text-after / progression are inherited from the outermost endpoints. Only TYPE_HIGHLIGHT
 * candidates reach here — [isMergeEligible] filters TYPE_IMAGE upstream.
 */
internal fun applyMerge(anchor: MergeAnchor, match: MergeCandidate): MergeAnchor {
    val neighbor = match.neighbor
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
 * When [html] is provided, an additional figure-gap check runs after [findAdjacency] succeeds: if
 * ANY figure element (`<img>`/`<svg>`/`<picture>`/`<figure>`) sits in the DOM char-range between
 * anchor and candidate, the pair is NOT merged. Rationale: two highlights created as separate
 * selections either side of a figure represent independent user intent — merging them would
 * silently annotate the figure. See the KDoc on [findAdjacency] for the void-figure motivation.
 * When [html] is null (unit-test paths without a chapter body) the gap check is skipped.
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
        val adjacency = findAdjacency(anchor, candidate) ?: continue
        if (html != null && hasFigureInGap(html, anchor, candidate, adjacency.side)) continue
        return adjacency
    }
    return null
}

/**
 * True when a figure element (`<img>`/`<svg>`/`<picture>`/`<figure>`) sits in the readable-text
 * gap between [anchor] and [candidate] in [html]. Void figures contribute zero readable chars, so
 * they're invisible to [findAdjacency]'s textual check — this walk over the gap catches them.
 *
 * Falls back to `false` when either range can't be located in the DOM: without positions we can't
 * verify there IS a figure between, so we don't block the merge. `findEnclosedFiguresInHtml`'s
 * straddle check is strict (`start < elemPos < end`), so an empty gap or a figure at exactly one
 * of the endpoints doesn't count.
 */
internal fun hasFigureInGap(
    html: String,
    anchor: MergeAnchor,
    candidate: Annotation,
    side: MergeSide,
): Boolean {
    val anchorStart = locateSnippetInBody(html, anchor.textSnippet, anchor.textBefore) ?: return false
    val anchorEnd = anchorStart + anchor.textSnippet.length
    val candStart = locateSnippetInBody(html, candidate.textSnippet, candidate.textBefore) ?: return false
    val candEnd = candStart + candidate.textSnippet.length
    val rawGapStart: Long
    val rawGapEnd: Long
    when (side) {
        MergeSide.CANDIDATE_AFTER_ANCHOR -> {
            rawGapStart = anchorEnd
            rawGapEnd = candStart
        }
        MergeSide.CANDIDATE_BEFORE_ANCHOR -> {
            rawGapStart = candEnd
            rawGapEnd = anchorStart
        }
    }
    // Widen the gap by 1 char on each side. Void figures (<img>) contribute zero readable chars,
    // so a figure sitting exactly at the boundary between anchor and candidate would have
    // `elemStart == rawGapStart == rawGapEnd` — the strict straddle in [findEnclosedFiguresInHtml]
    // (`startChar < elemStart < endChar`) would then never fire without widening. Widening by 1 on
    // each side is safe: the strict less-than still excludes figures sitting strictly inside
    // anchor's or candidate's own range.
    val gapStart = (rawGapStart - 1L).coerceAtLeast(0L)
    val gapEnd = rawGapEnd + 1L
    if (gapEnd <= gapStart) return false
    return findEnclosedFiguresInHtml(html, gapStart, gapEnd).isNotEmpty()
}

