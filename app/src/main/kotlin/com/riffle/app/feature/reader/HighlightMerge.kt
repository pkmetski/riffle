package com.riffle.app.feature.reader

import com.riffle.core.database.AnnotationEntity
import com.riffle.core.domain.Annotation

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
 * Static eligibility gate — same chapter, same colour, both notes empty/blank.
 * Adjacency is checked separately by [findAdjacency].
 */
internal fun isMergeEligible(anchor: MergeAnchor, candidate: Annotation): Boolean {
    if (candidate.type != AnnotationEntity.TYPE_HIGHLIGHT) return false
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
 */
internal fun findAnyMergeableNeighbor(
    anchor: MergeAnchor,
    pool: List<Annotation>,
    excludeIds: Set<String>,
): MergeCandidate? {
    for (candidate in pool) {
        if (candidate.id in excludeIds) continue
        if (!isMergeEligible(anchor, candidate)) continue
        val adjacency = findAdjacency(anchor, candidate) ?: continue
        return adjacency
    }
    return null
}
