package com.riffle.feature.reader

import kotlin.math.roundToInt

/**
 * Builds the human-readable title stored on a newly created EPUB bookmark.
 *
 * When a TOC-derived segment can be resolved for [href], the title uses the format
 * "Chapter Name · 42%" where the percentage is within-chapter progression. Otherwise
 * it falls back to whole-book progression ("42%") using [totalProgression] when available.
 *
 * The chapter name is truncated to 30 characters at a word boundary so the bookmark list
 * stays scannable — the same rule used by both the Android and iOS readers. Pure function;
 * no platform dependencies.
 */
fun buildEpubBookmarkTitle(
    segments: List<RailSegment>,
    href: String,
    spineHrefs: List<String>,
    progression: Double,
    totalProgression: Double?,
): String {
    val chapterTitle = if (segments.isNotEmpty()) {
        val segIdx = findActiveSegmentIndex(segments, href, spineHrefs)
        segments.getOrNull(segIdx)?.title?.takeIf { it.isNotBlank() }
            ?.let { if (it.length > 30) it.take(30).trimEnd { c -> !c.isLetterOrDigit() } + "…" else it }
    } else null
    return if (chapterTitle != null) {
        val pct = (progression * 100).roundToInt().coerceIn(0, 100)
        "$chapterTitle · $pct%"
    } else {
        val pct = ((totalProgression ?: progression) * 100).roundToInt().coerceIn(0, 100)
        "$pct%"
    }
}
