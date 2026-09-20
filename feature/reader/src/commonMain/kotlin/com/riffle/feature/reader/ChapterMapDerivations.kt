package com.riffle.feature.reader

import com.riffle.core.common.TimeRemaining
import com.riffle.core.domain.FormattingPreferences
import com.riffle.core.models.TocEntry

/**
 * The chapter-map derivations both readers need, lifted out of Android's `EpubReaderViewModel`
 * so the iOS reader computes the identical numbers instead of growing a second copy.
 *
 * Everything here is pure: the hosts own the flows (Android `StateFlow`s off Readium's publication,
 * iOS a `combine` over the navigator's position flow) and call these for the values the overlay
 * renders.
 */

/** The summed weight of every rail segment — "the whole book" in Readium position units. */
fun totalRailWeight(segments: List<RailSegment>): Float =
    segments.fold(0f) { acc, segment -> acc + segment.weight }

/** The summed weight of every segment before [index]. */
private fun weightBefore(segments: List<RailSegment>, index: Int): Float {
    var acc = 0f
    for (k in 0 until index.coerceIn(0, segments.size)) acc += segments[k].weight
    return acc
}

/**
 * Cursor position within the rail (0..1).
 *
 * Driven by [totalProgression] (monotonically increasing across the whole book) rather than
 * chapter progression (which resets to 0 at each new spine resource). Using chapter progression
 * made the cursor jump backward every time a new spine resource loaded inside the same segment
 * (e.g. reading SOL 376 → SOL 380 inside a single "Chapter 20" segment). The whole-book value is
 * converted to a within-segment fraction so the cursor still stays inside the active segment's
 * bounds however the segments are weighted.
 */
fun railCursorPositionForTotalProgression(
    activeIndex: Int,
    segments: List<RailSegment>,
    totalProgression: Float?,
): Float {
    if (totalProgression == null || segments.isEmpty()) return 0f
    val totalWeight = totalRailWeight(segments)
    if (totalWeight == 0f) return 0f
    val i = activeIndex.coerceIn(0, segments.size - 1)
    val before = weightBefore(segments, i)
    val segWeight = (segments.getOrNull(i)?.weight ?: 0f).coerceAtLeast(0f)
    val withinSeg = if (segWeight > 0f) {
        ((totalProgression * totalWeight - before) / segWeight).coerceIn(0f, 1f)
    } else {
        0f
    }
    return weightedRailCursorPosition(i, segments, withinSeg)
}

/**
 * Seconds of reading left in the active chapter, estimated from the persisted reading speed.
 *
 * The chapter's end is expressed as a fraction of the whole book, which works even when a TOC
 * entry spans several spine resources (a "Part I" title page followed by its chapter files),
 * because [totalProgression] increases monotonically across all of them.
 *
 * Returns null when there is nothing to estimate from — no segments, no weights, or no position.
 * A live readaloud/audiobook track produces an *exact* figure instead; that path stays with the
 * host because only it knows the track, and it calls [TimeRemaining.Exact] directly.
 */
fun estimatedChapterTimeRemaining(
    segments: List<RailSegment>,
    activeIndex: Int,
    totalProgression: Float?,
    speedSecPerPosition: Double,
): TimeRemaining? {
    val totalWeight = totalRailWeight(segments)
    if (totalWeight == 0f) return null
    val chapterWeight = segments.getOrNull(activeIndex.coerceIn(0, segments.size - 1))?.weight ?: return null
    val totalProg = totalProgression ?: return null
    val chapterEndFrac = (weightBefore(segments, activeIndex) + chapterWeight) / totalWeight
    val remainingFrac = (chapterEndFrac - totalProg).coerceAtLeast(0f)
    val sec = (remainingFrac * totalWeight * speedSecPerPosition).toLong().coerceAtLeast(0L)
    return TimeRemaining.Estimated(sec)
}

/** Seconds of reading left in the whole book, estimated from the persisted reading speed. */
fun estimatedBookTimeRemaining(
    segments: List<RailSegment>,
    totalProgression: Float?,
    speedSecPerPosition: Double,
): TimeRemaining? {
    val totalWeight = totalRailWeight(segments)
    if (totalWeight == 0f) return null
    val totalProg = totalProgression ?: return null
    val sec = ((1f - totalProg) * totalWeight * speedSecPerPosition).toLong().coerceAtLeast(0L)
    return TimeRemaining.Estimated(sec)
}

/**
 * Everything the chapter-map overlay renders, derived from one position.
 *
 * Android assembles the same values out of six separate `StateFlow`s inside its reader
 * ViewModel because each has its own recomposition scope there. iOS has no reader ViewModel, so
 * it assembles them in one call — [chapterMapUiState] — off the navigator's position flow. The
 * arithmetic is the same either way, which is the point of it living here.
 */
data class ChapterMapUiState(
    val segments: List<RailSegment>,
    val activeIndex: Int,
    val cursorPosition: Float,
    /** Whole-book progress for the percentage readout; falls back to the cursor. */
    val labelProgress: Float,
    val chapterTimeRemaining: TimeRemaining?,
    val bookTimeRemaining: TimeRemaining?,
) {
    companion object {
        val Empty = ChapterMapUiState(
            segments = emptyList(),
            activeIndex = 0,
            cursorPosition = 0f,
            labelProgress = 0f,
            chapterTimeRemaining = null,
            bookTimeRemaining = null,
        )
    }
}

/**
 * Build the whole chapter-map state for a book position.
 *
 * [spineHrefs] and [positionCounts] must be index-aligned (Readium's reading order and the size
 * of each resource's position list). When either is empty the rail still builds, but from the
 * TOC alone: `buildRailSegments`' length rule degrades to its grandchildren-only signal and every
 * segment gets weight 1. That is the "positions not computed yet" state, not an error.
 *
 * [currentHref] `null` means "no position yet" — the segments are returned with the cursor parked
 * at the start rather than the state being empty, so the rail paints as soon as the TOC is known.
 */
fun chapterMapUiState(
    tocEntries: List<TocEntry>,
    bookTitle: String,
    spineHrefs: List<String>,
    positionCounts: List<Int>,
    currentHref: String?,
    chapterProgression: Float,
    totalProgression: Float?,
    speedSecPerPosition: Double,
): ChapterMapUiState {
    if (tocEntries.isEmpty()) return ChapterMapUiState.Empty
    val segments = weightSegmentsByChapterLength(
        buildRailSegments(
            tocEntries,
            bookTitle,
            spineHrefs = spineHrefs,
            positionCounts = positionCounts,
        ),
        spineHrefs,
        positionCounts,
    )
    if (segments.isEmpty()) return ChapterMapUiState.Empty
    val activeIndex = if (currentHref == null) {
        0
    } else {
        findActiveSegmentIndex(segments, currentHref, spineHrefs, chapterProgression)
    }
    val cursorPosition = railCursorPositionForTotalProgression(activeIndex, segments, totalProgression)
    return ChapterMapUiState(
        segments = segments,
        activeIndex = activeIndex,
        cursorPosition = cursorPosition,
        // Mirrors Android's `EpubChapterRailOverlay`: prefer the navigator's whole-book
        // progression, fall back to the rail cursor before Readium has produced one.
        labelProgress = totalProgression?.takeIf { it > 0f } ?: cursorPosition,
        chapterTimeRemaining = estimatedChapterTimeRemaining(
            segments,
            activeIndex,
            totalProgression,
            speedSecPerPosition,
        ),
        bookTimeRemaining = estimatedBookTimeRemaining(segments, totalProgression, speedSecPerPosition),
    )
}

/**
 * Whether the on-screen-info overlay has anything to show.
 *
 * Any one of the five Display toggles being on is enough — the rail can be hidden while the
 * labels are on, and vice versa. Both readers gate the overlay on this so a user who turns the
 * chapter map off but leaves "Time remaining" on still gets the readout.
 */
fun chapterMapVisible(prefs: FormattingPreferences): Boolean =
    prefs.showChapterMap ||
        prefs.showReadingProgressLabels ||
        prefs.showCurrentChapterLabel ||
        prefs.showReadingTimeEstimate
