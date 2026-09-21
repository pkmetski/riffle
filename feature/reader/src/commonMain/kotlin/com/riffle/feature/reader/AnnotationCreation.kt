package com.riffle.feature.reader

import com.riffle.core.database.AnnotationEntity
import com.riffle.core.models.Annotation
import com.riffle.core.models.EmbeddedFigure
import com.riffle.core.models.EmphasisStyle

/**
 * The two decisions that turn a raw text selection into a stored highlight, extracted so both
 * hosts make them identically.
 *
 * They used to live only inside Android's `EpubReaderViewModel` (`createHighlight` and
 * `commitDraft`), which is why iOS had no way to create an annotation at all: every primitive it
 * needed — [locateSnippetInBody], [buildHighlightCfiRange], [computeOverlapMerge],
 * [computeAdjacentCreateMerge] — was already shared, but the *order* they have to be applied in,
 * and what to do with each result, was not. Re-deriving that order on iOS would have been the
 * "private platform copy of a shared derivation" AGENTS.md forbids; two subtly different merge
 * policies would compile, both suites would stay green, and the same book would grow a different
 * set of highlights on each device.
 *
 * Everything here is pure: `html` in, decisions out. The store writes stay with the caller
 * because the two hosts reach their `AnnotationStore` through different lifecycles.
 */

/**
 * The "no captured value" marker for `AnnotationEntity.originFontFamily` (issue #484).
 *
 * The column has a non-null contract, but a bookmark has no live text selection to read a font
 * from and a selection-teardown race can leave the stash empty. Rows stamped with this are
 * treated as "no value" by every renderer and healed by
 * `AnnotationStore.healSentinelOriginFontFamily` on the next open. Plain `serif` for backwards
 * compatibility with rows written before the regression fix.
 *
 * Shared so iOS stamps the same sentinel: a different marker there would make the heal query —
 * which matches on this exact string — silently skip every annotation made on an iPhone.
 */
const val FALLBACK_ORIGIN_FONT_FAMILY = "serif"

/**
 * Where a fresh selection actually sits in its chapter.
 *
 * Readium reports `Locator.locations.progression` as the **page's** start in paginated mode, not
 * the selection's, on both platforms. Every highlight made on one page would otherwise share a
 * progression and the annotations panel — which sorts by progression then createdAt — would list
 * same-page highlights in creation order instead of reading order.
 */
data class HighlightAnchor(
    /** True within-chapter progression of the selection start, 0..1. */
    val progression: Double,
    /** `epubcfi(…)` range covering the selection. */
    val cfiRange: String,
    /** Readable-char range used to find figures the selection encloses. */
    val figureRangeStartChar: Long,
    /** Exclusive end of [figureRangeStartChar]'s range. */
    val figureRangeEndChar: Long,
)

/**
 * Resolve [snippet]'s true position in [html] and build its CFI range.
 *
 * [pageProgression] is the navigator's own (page-level) value, used only when the snippet cannot
 * be located in the body — a revised resource, or a selection that crossed into markup the
 * readable-text walk skips.
 *
 * Returns null when no CFI range can be built at all; the caller must not persist a highlight
 * without one.
 */
fun buildHighlightAnchor(
    html: String,
    spineIndex: Int,
    snippet: String,
    textBefore: String,
    pageProgression: Double,
): HighlightAnchor? {
    val spineStep = (spineIndex + 1) * 2
    // Same count Android's `countBodyChars(Jsoup.parse(html).body())` produces: both sum the
    // lengths of non-blank text nodes, one through jsoup and one through ksoup.
    val totalChars = countReadableBodyChars(html)
    val locatedChar = if (totalChars > 0) locateSnippetInBody(html, snippet, textBefore) else null
    val progression = if (locatedChar != null && totalChars > 0) {
        locatedChar.toDouble() / totalChars.toDouble()
    } else {
        pageProgression
    }
    val cfiRange = if (locatedChar != null) {
        // End char via a non-whitespace walk, not `snippet.length`: a multi-paragraph selection
        // like "para1.\nThe" is 10 chars but the readable body holds "para1.The" (9), because
        // blank-only text nodes between block elements are skipped. Using the raw length
        // overshoots by the newline count and the wash extends past the user's selection —
        // reported 2026-07-20 as "wash extends to whole paragraph".
        val endExclusive = snippetEndCharInBody(html, locatedChar, snippet)
        buildHighlightCfiRange(spineStep, html, locatedChar, (endExclusive - 1L).coerceAtLeast(locatedChar))
    } else {
        buildHighlightCfiRangeForSelection(spineStep, html, progression, snippet)
    } ?: return null
    // Anchor the figure search to the snippet's real position: progression is off by 40-60 chars
    // mid-chapter, which pushes the endpoint one char short of an enclosed figure and misses it.
    val (startChar, endChar) = anchorRangeToSnippet(
        html = html,
        snippet = snippet,
        textBefore = textBefore,
        progression = progression,
    )
    return HighlightAnchor(
        progression = progression,
        cfiRange = cfiRange,
        figureRangeStartChar = startChar,
        figureRangeEndChar = endChar,
    )
}

/**
 * Everything the caller must do to persist a draft: which rows to tombstone first, and the
 * final field values of the row to create.
 */
data class HighlightCommitPlan(
    /** The range fields to persist — the draft's own, or the union produced by a merge. */
    val fields: MergedDraftFields,
    /** Live `TYPE_HIGHLIGHT` rows absorbed by this commit. Delete before creating. */
    val deleteHighlightIds: List<String>,
    /**
     * Sibling `TYPE_EMPHASIS` rows anchored at an absorbed highlight's CFI.
     *
     * ADR 0056 §4 requires cascading these: a merge that leaves them behind orphans a
     * TYPE_EMPHASIS row with no live anchor, and the user sees "the annotation is gone from the
     * panel but the text stays bold forever" (29 orphans observed on a debug device 2026-07-18).
     */
    val deleteEmphasisIds: List<String>,
    /** Standalone `TYPE_IMAGE` rows for figures the merged highlight now encloses. */
    val deleteImageIds: List<String>,
    /**
     * False when nothing merged. The draft's inline-formatted `textSnippetHtml` may only be
     * carried onto the created row in that case: after a merge [fields] spans more than the
     * original selection and grafting the narrow HTML on would misalign the formatting.
     */
    val carrySnippetHtml: Boolean,
)

/**
 * Decide the overlap merge, then the adjacency merge, then figure absorption — in that order,
 * because each consumes the previous one's output.
 *
 * [candidates] and [imageAnnotations] must already be filtered to live rows of their type in the
 * draft's chapter; [emphasisPool] is the book's live `TYPE_EMPHASIS` rows. [html] is nullable so
 * a caller that could not read the chapter still gets a usable plan (no merges, draft fields
 * verbatim) rather than being unable to create the highlight at all.
 */
fun planHighlightCommit(
    html: String?,
    draftFields: MergedDraftFields,
    draftSpineIndex: Int,
    draftChapterHref: String,
    draftColor: String,
    draftEmphasisStyles: Set<EmphasisStyle>,
    candidates: List<Annotation>,
    emphasisPool: List<Annotation>,
    imageAnnotations: List<Annotation>,
): HighlightCommitPlan {
    val overlapMerge = html?.let {
        computeOverlapMerge(
            html = it,
            draftSnippet = draftFields.textSnippet,
            draftTextBefore = draftFields.textBefore,
            candidates = candidates,
            draftEmphasisStyles = draftEmphasisStyles,
            emphasisPool = emphasisPool,
        )
    }
    val overlapFields: MergedDraftFields = if (html != null && overlapMerge != null) {
        buildMergedDraftFields(
            html = html,
            draftSpineIndex = draftSpineIndex,
            draftEmbeddedFigures = draftFields.embeddedFigures,
            overlap = overlapMerge,
            candidates = candidates,
        ) ?: draftFields
    } else {
        draftFields
    }

    val overlapVictimIds = overlapMerge?.victimIds?.toSet() ?: emptySet()
    val adjacentCandidates = candidates.filter { it.id !in overlapVictimIds }
    val adjacentMerge = html?.let {
        computeAdjacentCreateMerge(
            html = it,
            draftSnippet = overlapFields.textSnippet,
            draftTextBefore = overlapFields.textBefore,
            draftTextAfter = overlapFields.textAfter,
            draftProgression = overlapFields.progression,
            draftSpineIndex = draftSpineIndex,
            draftChapterHref = draftChapterHref,
            draftColor = draftColor,
            draftEmbeddedFigures = overlapFields.embeddedFigures,
            candidates = adjacentCandidates,
            draftEmphasisStyles = draftEmphasisStyles,
            emphasisPool = emphasisPool,
        )
    }

    val victimIds = overlapVictimIds + (adjacentMerge?.victimIds?.toSet() ?: emptySet())
    val victimCfis = candidates.filter { it.id in victimIds }.map { it.cfi }.toSet()
    val emphasisVictimIds = emphasisPool.filter { it.cfi in victimCfis }.map { it.id }

    val mergedFields = adjacentMerge?.fields ?: overlapFields
    val absorbedFilenames = mergedFields.embeddedFigures
        ?.mapNotNull { it.href }
        ?.map(::figureHrefFilename)
        ?.toSet()
        .orEmpty()
    val deleteImageIds = if (absorbedFilenames.isEmpty()) {
        emptyList()
    } else {
        imageAnnotations
            .filter { it.type == AnnotationEntity.TYPE_IMAGE }
            .filter { it.imageHref?.let(::figureHrefFilename) in absorbedFilenames }
            .map { it.id }
    }

    return HighlightCommitPlan(
        fields = mergedFields,
        // Ordered overlap-first then adjacency so a caller that logs the plan reads it in the
        // order the decisions were made.
        deleteHighlightIds = overlapVictimIds.toList() + (adjacentMerge?.victimIds ?: emptyList()),
        deleteEmphasisIds = emphasisVictimIds,
        deleteImageIds = deleteImageIds,
        carrySnippetHtml = overlapMerge == null && adjacentMerge == null,
    )
}

/** Convenience for callers assembling the draft's own fields before any merge. */
fun draftFieldsOf(
    cfiRange: String,
    textSnippet: String,
    textBefore: String,
    textAfter: String,
    progression: Double,
    embeddedFigures: List<EmbeddedFigure>?,
): MergedDraftFields = MergedDraftFields(
    cfiRange = cfiRange,
    textSnippet = textSnippet,
    textBefore = textBefore,
    textAfter = textAfter,
    progression = progression,
    embeddedFigures = embeddedFigures,
)
